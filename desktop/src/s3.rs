//! Minimal S3 client (PUT, GET, list) with AWS Signature V4, same scheme as the Android app.
use chrono::Utc;
use hmac::{Hmac, Mac};
use sha2::{Digest, Sha256};
use std::{collections::BTreeMap, fs, io::Read, path::Path};

pub struct Config { pub key_id: String, pub secret: String, pub region: String, pub bucket: String, pub prefix: String }

impl Config {
    /// Reads a `key=value` properties file (the same `aws.properties` as the Android build).
    pub fn load(path: &Path) -> Result<Config, String> {
        let text = fs::read_to_string(path).map_err(|e| format!("{}: {e}", path.display()))?;
        let map: BTreeMap<_, _> = text.lines().filter(|l| !l.trim_start().starts_with('#'))
            .filter_map(|l| l.split_once('=')).map(|(k, v)| (k.trim().to_string(), v.trim().to_string())).collect();
        let get = |k: &str| map.get(k).filter(|v| !v.is_empty()).cloned().ok_or(format!("{}: missing {k}", path.display()));
        Ok(Config { key_id: get("key_id")?, secret: get("secret")?, region: get("region")?, bucket: get("bucket")?, prefix: get("prefix")? })
    }
}

pub struct Obj { pub name: String, pub etag: String }

fn hex(b: &[u8]) -> String { b.iter().map(|x| format!("{x:02x}")).collect() }
pub fn sha256_hex(b: &[u8]) -> String { hex(&Sha256::digest(b)) }
fn hmac(key: &[u8], data: &str) -> Vec<u8> {
    let mut m = Hmac::<Sha256>::new_from_slice(key).unwrap();
    m.update(data.as_bytes());
    m.finalize().into_bytes().to_vec()
}

/// RFC 3986 percent-encoding, as SigV4 wants it.
pub fn enc(s: &str) -> String {
    s.bytes().map(|b| if b.is_ascii_alphanumeric() || b"-_.~".contains(&b) { (b as char).to_string() } else { format!("%{b:02X}") }).collect()
}

pub fn authorization(method: &str, host: &str, path: &str, query: &str, payload_hash: &str, amz_date: &str, cfg: &Config) -> String {
    let day = &amz_date[..8];
    let signed = "host;x-amz-content-sha256;x-amz-date";
    let canonical = [method, path, query, &format!("host:{host}\nx-amz-content-sha256:{payload_hash}\nx-amz-date:{amz_date}\n"), signed, payload_hash].join("\n");
    let scope = format!("{day}/{}/s3/aws4_request", cfg.region);
    let to_sign = format!("AWS4-HMAC-SHA256\n{amz_date}\n{scope}\n{}", sha256_hex(canonical.as_bytes()));
    let mut k = hmac(format!("AWS4{}", cfg.secret).as_bytes(), day);
    for part in [cfg.region.as_str(), "s3", "aws4_request"] { k = hmac(&k, part); }
    format!("AWS4-HMAC-SHA256 Credential={}/{scope}, SignedHeaders={signed}, Signature={}", cfg.key_id, hex(&hmac(&k, &to_sign)))
}

fn request(cfg: &Config, method: &str, path: &str, query: &str, body: Option<&[u8]>) -> Result<(Vec<u8>, String), String> {
    let host = format!("{}.s3.{}.amazonaws.com", cfg.bucket, cfg.region);
    let amz_date = Utc::now().format("%Y%m%dT%H%M%SZ").to_string();
    let payload_hash = sha256_hex(body.unwrap_or(&[]));
    let url = format!("https://{host}{path}{}", if query.is_empty() { String::new() } else { format!("?{query}") });
    let mut req = ureq::request(method, &url)
        .set("x-amz-date", &amz_date).set("x-amz-content-sha256", &payload_hash)
        .set("Authorization", &authorization(method, &host, path, query, &payload_hash, &amz_date, cfg));
    let res = match body {
        Some(b) => { req = req.set("Content-Type", "application/json"); req.send_bytes(b) }
        None => req.call(),
    };
    match res {
        Ok(r) => {
            let etag = r.header("ETag").unwrap_or("").trim_matches('"').to_string();
            let mut out = Vec::new();
            r.into_reader().read_to_end(&mut out).map_err(|e| e.to_string())?;
            Ok((out, etag))
        }
        Err(ureq::Error::Status(code, r)) => Err(format!("S3 {code} {}", r.into_string().unwrap_or_default().chars().take(300).collect::<String>())),
        Err(e) => Err(e.to_string()),
    }
}

fn object_path(cfg: &Config, name: &str) -> String {
    assert!(!name.is_empty() && name.bytes().all(|b| b.is_ascii_alphanumeric() || b"._-".contains(&b)), "bad object name {name}");
    format!("/{}/{name}", cfg.prefix)
}

pub fn put(cfg: &Config, name: &str, body: &[u8]) -> Result<String, String> { request(cfg, "PUT", &object_path(cfg, name), "", Some(body)).map(|r| r.1) }
pub fn get(cfg: &Config, name: &str) -> Result<Vec<u8>, String> { request(cfg, "GET", &object_path(cfg, name), "", None).map(|r| r.0) }

fn unescape(s: &str) -> String { s.replace("&quot;", "\"").replace("&apos;", "'").replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&") }
fn tag<'a>(s: &'a str, name: &str) -> Option<&'a str> {
    let (open, close) = (format!("<{name}>"), format!("</{name}>"));
    let a = s.find(&open)? + open.len();
    Some(&s[a..a + s[a..].find(&close)?])
}

/// Parses a ListObjectsV2 page: names lose `prefix`; sub-folders and the prefix itself are skipped.
pub fn parse_list(xml: &str, prefix: &str) -> (Vec<Obj>, Option<String>) {
    let objs = xml.split("<Contents>").skip(1).filter_map(|c| {
        let c = c.split("</Contents>").next()?;
        let key = unescape(tag(c, "Key")?);
        let name = key.strip_prefix(prefix)?.to_string();
        if name.is_empty() || name.contains('/') { return None; }
        Some(Obj { name, etag: unescape(tag(c, "ETag").unwrap_or("")).trim_matches('"').to_string() })
    }).collect();
    let next = if tag(xml, "IsTruncated") == Some("true") { tag(xml, "NextContinuationToken").map(unescape) } else { None };
    (objs, next)
}

pub fn list(cfg: &Config) -> Result<Vec<Obj>, String> {
    let (mut out, mut token) = (Vec::new(), None::<String>);
    let prefix = format!("{}/", cfg.prefix);
    loop {
        let mut params = BTreeMap::from([("list-type", "2".to_string()), ("prefix", prefix.clone())]);
        if let Some(t) = &token { params.insert("continuation-token", t.clone()); }
        let q = params.iter().map(|(k, v)| format!("{}={}", enc(k), enc(v))).collect::<Vec<_>>().join("&");
        let xml = String::from_utf8_lossy(&request(cfg, "GET", "/", &q, None)?.0).into_owned();
        let (objs, next) = parse_list(&xml, &prefix);
        out.extend(objs);
        match next { Some(t) => token = Some(t), None => return Ok(out) }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn encodes_like_rfc3986() { assert_eq!(enc("a b/é~"), "a%20b%2F%C3%A9~"); }

    #[test]
    fn parses_a_listing() {
        let xml = "<R><IsTruncated>true</IsTruncated><NextContinuationToken>t+1</NextContinuationToken>\
            <Contents><Key>p/a.json</Key><ETag>&quot;e1&quot;</ETag></Contents>\
            <Contents><Key>p/</Key><ETag>&quot;e0&quot;</ETag></Contents>\
            <Contents><Key>p/sub/b.json</Key><ETag>&quot;e2&quot;</ETag></Contents></R>";
        let (o, next) = parse_list(xml, "p/");
        assert_eq!(o.len(), 1);
        assert_eq!((o[0].name.as_str(), o[0].etag.as_str()), ("a.json", "e1"));
        assert_eq!(next.as_deref(), Some("t+1"));
    }

    #[test]
    fn hashes_the_empty_payload() {
        assert_eq!(sha256_hex(b""), "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
    }
}
