/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/**
 * Pure-JS SHA-1 implementation. Used instead of crypto.subtle.digest because
 * file:// origins are not treated as a secure context by Android WebView, and
 * crypto.subtle is only available in secure contexts (https://, localhost).
 *
 * Based on the public-domain SHA-1 algorithm (FIPS PUB 180-4).
 */
function sha1Hex(message) {
  // Convert string to UTF-8 byte array
  const utf8Bytes = [];
  for (let i = 0; i < message.length; i++) {
    let c = message.charCodeAt(i);
    if (c < 0x80) {
      utf8Bytes.push(c);
    } else if (c < 0x800) {
      utf8Bytes.push(0xc0 | (c >> 6), 0x80 | (c & 0x3f));
    } else if (c >= 0xd800 && c <= 0xdbff && i + 1 < message.length) {
      // surrogate pair
      const next = message.charCodeAt(i + 1);
      const codePoint = 0x10000 + ((c - 0xd800) << 10) + (next - 0xdc00);
      utf8Bytes.push(
        0xf0 | (codePoint >> 18),
        0x80 | ((codePoint >> 12) & 0x3f),
        0x80 | ((codePoint >> 6) & 0x3f),
        0x80 | (codePoint & 0x3f)
      );
      i++;
    } else {
      utf8Bytes.push(0xe0 | (c >> 12), 0x80 | ((c >> 6) & 0x3f), 0x80 | (c & 0x3f));
    }
  }

  // SHA-1 uses 32-bit big-endian words
  function rotLeft(n, s) { return (n << s) | (n >>> (32 - s)); }
  function toHex(n) { return ('00000000' + (n >>> 0).toString(16)).slice(-8); }

  // Pre-process: pad to 512-bit block boundary (64 bytes)
  const msgLen = utf8Bytes.length;
  // append bit '1' (byte 0x80)
  utf8Bytes.push(0x80);
  // append zeros until length ≡ 56 (mod 64)
  while (utf8Bytes.length % 64 !== 56) utf8Bytes.push(0);
  // append original length in bits as 64-bit big-endian
  const bitLen = msgLen * 8;
  utf8Bytes.push(0, 0, 0, 0); // high 32 bits (we don't support msgs > 2^32 bits)
  utf8Bytes.push(
    (bitLen >>> 24) & 0xff,
    (bitLen >>> 16) & 0xff,
    (bitLen >>> 8) & 0xff,
    bitLen & 0xff
  );

  // Initial hash values
  let H0 = 0x67452301;
  let H1 = 0xefcdab89;
  let H2 = 0x98badcfe;
  let H3 = 0x10325476;
  let H4 = 0xc3d2e1f0;

  // Process each 512-bit (64-byte) block
  for (let blockStart = 0; blockStart < utf8Bytes.length; blockStart += 64) {
    const W = new Array(80);
    for (let t = 0; t < 16; t++) {
      W[t] = (utf8Bytes[blockStart + t * 4] << 24)
            | (utf8Bytes[blockStart + t * 4 + 1] << 16)
            | (utf8Bytes[blockStart + t * 4 + 2] << 8)
            |  utf8Bytes[blockStart + t * 4 + 3];
    }
    for (let t = 16; t < 80; t++) {
      W[t] = rotLeft(W[t-3] ^ W[t-8] ^ W[t-14] ^ W[t-16], 1);
    }

    let a = H0, b = H1, c = H2, d = H3, e = H4;

    for (let t = 0; t < 80; t++) {
      let temp;
      if (t < 20) {
        temp = (rotLeft(a, 5) + ((b & c) | (~b & d)) + e + W[t] + 0x5a827999) >>> 0;
      } else if (t < 40) {
        temp = (rotLeft(a, 5) + (b ^ c ^ d) + e + W[t] + 0x6ed9eba1) >>> 0;
      } else if (t < 60) {
        temp = (rotLeft(a, 5) + ((b & c) | (b & d) | (c & d)) + e + W[t] + 0x8f1bbcdc) >>> 0;
      } else {
        temp = (rotLeft(a, 5) + (b ^ c ^ d) + e + W[t] + 0xca62c1d6) >>> 0;
      }
      e = d; d = c; c = rotLeft(b, 30); b = a; a = temp;
    }

    H0 = (H0 + a) >>> 0;
    H1 = (H1 + b) >>> 0;
    H2 = (H2 + c) >>> 0;
    H3 = (H3 + d) >>> 0;
    H4 = (H4 + e) >>> 0;
  }

  return toHex(H0) + toHex(H1) + toHex(H2) + toHex(H3) + toHex(H4);
}

async function digestMessage(message) {
  const hashHex = sha1Hex(message);
  return { result: hashHex };
}

window['ai_edge_gallery_get_result'] = async (data) => {
  try {
    const jsonData = JSON.parse(data);
    return JSON.stringify(await digestMessage(jsonData['text']));
  } catch (e) {
    console.error(e);
    return JSON.stringify({error: `Failed to calculate hash: ${e.message}`});
  }
};
