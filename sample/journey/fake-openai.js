// A tiny OpenAI-compatible streaming server for the sample-app journey. No dependencies.
// Usage: node fake-openai.js <port> <name>
// It answers GET /v1/models and POST /v1/chat/completions (server-sent events), replying with
// "<name> says hello". It never prints request bodies.
const http = require("http");
const [port, name] = [Number(process.argv[2]), process.argv[3] || "fake"];

http.createServer((req, res) => {
  let size = 0;
  req.on("data", (chunk) => { size += chunk.length; });
  req.on("end", () => {
    console.log(`${new Date().toISOString()} ${name} ${req.method} ${req.url} (${size} bytes)`);
    if (req.method === "GET" && req.url === "/v1/models") {
      res.writeHead(200, { "Content-Type": "application/json" });
      return res.end(JSON.stringify({ data: [{ id: "fake-model" }] }));
    }
    if (req.method === "POST" && req.url === "/v1/chat/completions") {
      res.writeHead(200, { "Content-Type": "text/event-stream" });
      const words = [name, " says", " hello"];
      let i = 0;
      const timer = setInterval(() => {
        if (i < words.length) {
          res.write(`data: ${JSON.stringify({ choices: [{ index: 0, delta: { content: words[i++] }, finish_reason: null }] })}\n\n`);
        } else {
          res.write(`data: ${JSON.stringify({ choices: [{ index: 0, delta: {}, finish_reason: "stop" }] })}\n\n`);
          res.write("data: [DONE]\n\n");
          res.end();
          clearInterval(timer);
        }
      }, 40);
      res.on("close", () => clearInterval(timer));
      return;
    }
    res.writeHead(404).end();
  });
}).listen(port, "127.0.0.1", () => console.log(`${name} listening on 127.0.0.1:${port}`));
