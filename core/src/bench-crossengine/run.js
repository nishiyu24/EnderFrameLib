const http=require('http'),fs=require('fs'),path=require('path'),cp=require('child_process'),os=require('os');
const [,, browser, doc, tag] = process.argv;
const exe = {chrome:'C:/Program Files/Google/Chrome/Application/chrome.exe', edge:'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe', firefox:'C:/Program Files/Mozilla Firefox/firefox.exe'}[browser];
const dir=__dirname; let child;
const srv=http.createServer((req,res)=>{
  if(req.method==='POST'){let b='';req.on('data',d=>b+=d);req.on('end',()=>{fs.writeFileSync(path.join(dir,`res-${browser}-${doc}.json`),b);res.end('ok');console.log('got result');setTimeout(()=>{child.kill();srv.close();process.exit(0)},300);});return;}
  const u=req.url.split('?')[0]; const f=u==='/showcase.html'?path.join(__dirname,'../../../src/main/resources/data/enderframelib/enderframe_ui/showcase.html'):path.join(dir,u==='/'?'bench.html':u);
  if(fs.existsSync(f)){res.setHeader('Content-Type',f.endsWith('.html')?'text/html':'text/plain');res.end(fs.readFileSync(f));}else{res.statusCode=404;res.end();}
}).listen(0,()=>{
  const port=srv.address().port; const url=`http://127.0.0.1:${port}/bench.html?doc=${doc}`;
  const prof=fs.mkdtempSync(path.join(os.tmpdir(),'prof-'));
  let args;
  if(browser==='firefox') args=['-headless','-no-remote','-profile',prof,url];
  else args=['--headless=new','--disable-gpu-vsync','--no-first-run','--user-data-dir='+prof,'--window-size=900,700',url];
  child=cp.spawn(exe,args,{stdio:'ignore'});
  setTimeout(()=>{console.log('timeout');child.kill();process.exit(2)},180000);
});
