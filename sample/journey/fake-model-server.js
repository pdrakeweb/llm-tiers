// Serves a deterministic fake "model" file slowly, for the model-download journey. No dependencies.
// Usage: node fake-model-server.js <port> <megabytes> <kilobytes-per-second>
// Prints "sha256=<hex> size=<bytes>" on start. Supports Range requests, which the Android
// DownloadManager uses to resume.
const http = require("http");
const crypto = require("crypto");
const [port, megabytes, kbps] = [Number(process.argv[2]), Number(process.argv[3] || 8), Number(process.argv[4] || 1024)];
const body = Buffer.alloc(megabytes * 1024 * 1024);
for (let i = 0; i < body.length; i++) body[i] = (i * 31 + (i >> 8)) & 0xff;
const sha = crypto.createHash("sha256").update(body).digest("hex");

http.createServer((req, res) => {
  let start = 0;
  const range = /bytes=(\d+)-/.exec(req.headers.range || "");
  if (range) start = Number(range[1]);
  console.log(`${new Date().toISOString()} ${req.method} ${req.url} from byte ${start}`);
  res.writeHead(range ? 206 : 200, {
    "Content-Type": "application/octet-stream",
    "Content-Length": body.length - start,
    "Accept-Ranges": "bytes",
    ETag: `"${sha.slice(0, 16)}"`,
    ...(range ? { "Content-Range": `bytes ${start}-${body.length - 1}/${body.length}` } : {}),
  });
  if (req.method === "HEAD") return res.end();
  const step = Math.max(1, Math.floor((kbps * 1024) / 10));
  let offset = start;
  const timer = setInterval(() => {
    if (offset >= body.length) {
      clearInterval(timer);
      return res.end();
    }
    res.write(body.subarray(offset, Math.min(offset + step, body.length)));
    offset += step;
  }, 100);
  res.on("close", () => clearInterval(timer));
}).listen(port, "127.0.0.1", () => console.log(`sha256=${sha} size=${body.length}`));
