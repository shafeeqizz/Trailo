// Local device-test helper. Does not record request bodies, headers, or secrets.
const http = require('node:http');
if (process.argv.length !== 2) throw new Error('No arguments are supported.');
const pending = [];
const endpoints = ['createConvoy','createConvoyInvitation','joinConvoy','leaveConvoy','endConvoy','resolveConvoyOperation'];
for (const [port, mode] of [[5002, 'delay'], [5003, 'lost'], [5004, 'truncated'], [5005, 'held']]) {
http.createServer((request, response) => {
  if (mode === 'held' && request.method === 'GET' && request.url === '/release') {
    for (const release of pending.splice(0)) release();
    response.writeHead(200).end(); return;
  }
  const endpoint = request.url?.split('/').pop();
  if (request.method !== 'POST' || !endpoints.includes(endpoint) || request.url !== `/demo-trailo/us-central1/${endpoint}`) {
    response.writeHead(404).end();
    return;
  }
  const forward = body => {
  const upstream = http.request({hostname:'127.0.0.1', port:5001,
    path:request.url, method:'POST', headers:request.headers}, incoming => {
    const chunks = [];
    incoming.on('data', chunk => chunks.push(chunk));
    incoming.on('end', () => {
      if (endpoint === 'resolveConvoyOperation') {
        response.writeHead(incoming.statusCode, {'Content-Type':'application/json'});
        response.end(Buffer.concat(chunks));
      } else if (mode === 'truncated') {
        response.writeHead(incoming.statusCode, {'Content-Type':'application/json', 'Content-Length':100000});
        response.write('{');
        setTimeout(() => response.destroy(),100);
      } else if (mode === 'lost') {
        // The success reply is lost after commit; the transport reports unavailable.
        response.writeHead(503, {'Content-Type':'application/json'});
        response.end(JSON.stringify({error:{status:'UNAVAILABLE', message:'Local response lost.'}}));
      } else {
        setTimeout(() => {
          response.writeHead(incoming.statusCode, {'Content-Type':'application/json'});
          response.end(Buffer.concat(chunks));
        }, mode === 'delay' ? 22000 : 0);
      }
      console.log(`Local ${mode} probe: upstream completed.`);
    });
  });
  upstream.setTimeout(60000, () => upstream.destroy());
  upstream.on('error', () => { response.writeHead(503).end(); });
  upstream.end(body);
  };
  const body=[];
  request.on('data',chunk=>body.push(chunk));
  request.on('end',()=>{
    const send=()=>forward(Buffer.concat(body));
    if(mode==='held' && endpoint !== 'resolveConvoyOperation') pending.push(send);
    else send();
  });
}).listen(port, '127.0.0.1', () => console.log(`Local ${mode} proxy listening on 127.0.0.1:${port}.`));
}
