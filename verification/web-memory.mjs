// Engine-only before/after probe. RSS is process memory, not isolated allocator usage.
import fs from 'node:fs';
import {spawnSync, execFileSync} from 'node:child_process';
import {pathToFileURL} from 'node:url';
import {createRequire} from 'node:module';
const config=JSON.parse(fs.readFileSync(process.argv[2],'utf8'));
const require=createRequire(import.meta.url);
const {chromium}=require(config.root+'/verification/node_modules/playwright');
const nodeScript=`import {createGdalWorker} from ${JSON.stringify(pathToFileURL(config.assets+'/index.mjs').href)};
const before=process.memoryUsage().rss;let peak=before;
const timer=setInterval(()=>peak=Math.max(peak,process.memoryUsage().rss),5);
const bridge=await createGdalWorker();const initialized=process.memoryUsage().rss;
await new Promise(r=>setTimeout(r,1000));clearInterval(timer);
console.log(JSON.stringify({beforeRssBytes:before,initializedRssBytes:initialized,sampledPeakRssBytes:peak,increaseBytes:initialized-before}));
await bridge.close();`;
const nodeProbe=config.output+'.node.mjs';fs.writeFileSync(nodeProbe,nodeScript);
const nodeRuns=[];
for(let i=0;i<3;i++){
    const run=spawnSync(process.execPath,[nodeProbe],{encoding:'utf8'});
    if(run.status!==0)throw new Error(run.stderr);
    nodeRuns.push(JSON.parse(run.stdout));
}
const browserRuns=[];
for(let i=0;i<3;i++){
    const options={headless:true,args:process.platform==='linux'?['--no-sandbox']:[]};
    if(process.env.CHROME_BIN)options.executablePath=process.env.CHROME_BIN;
    const browser=await chromium.launch(options);
    try{
        const page=await browser.newPage();await page.goto(config.url);
        const cdp=await browser.newBrowserCDPSession();
        async function rss(){
            const {processInfo}=await cdp.send('SystemInfo.getProcessInfo');
            const ids=processInfo.filter(p=>p.type==='renderer').map(p=>p.id);
            return execFileSync('ps',['-o','rss=','-p',ids.join(',')],{encoding:'utf8'}).trim().split(/\s+/).reduce((sum,n)=>sum+Number(n)*1024,0);
        }
        const before=await rss();let peak=before,active=true;
        const sampler=(async()=>{while(active){peak=Math.max(peak,await rss());await new Promise(r=>setTimeout(r,50));}})();
        await page.evaluate(async url=>{const {createGdalWorker}=await import(url+'/assets/index.mjs');globalThis.bridge=await createGdalWorker();},config.url);
        const initialized=await rss();peak=Math.max(peak,initialized);
        const pageCdp=await page.context().newCDPSession(page);
        const worker=(await cdp.send('Target.getTargets')).targetInfos.find(t=>t.type==='worker');
        const {sessionId}=await cdp.send('Target.attachToTarget',{targetId:worker.targetId,flatten:false});
        let sequence=0;
        async function command(method){
            const id=++sequence;
            return new Promise((resolve,reject)=>{
                const listener=event=>{if(event.sessionId!==sessionId)return;const response=JSON.parse(event.message);if(response.id!==id)return;cdp.off('Target.receivedMessageFromTarget',listener);response.error?reject(new Error(JSON.stringify(response.error))):resolve(response.result);};
                cdp.on('Target.receivedMessageFromTarget',listener);
                cdp.send('Target.sendMessageToTarget',{sessionId,message:JSON.stringify({id,method})}).catch(reject);
            });
        }
        await pageCdp.send('HeapProfiler.collectGarbage');await command('HeapProfiler.collectGarbage');
        const afterGc=await rss();peak=Math.max(peak,afterGc);const workerHeap=await command('Runtime.getHeapUsage');
        active=false;await sampler;
        browserRuns.push({beforeRendererRssBytes:before,initializedRendererRssBytes:initialized,sampledPeakRendererRssBytes:peak,afterGcRendererRssBytes:afterGc,increaseAfterGcBytes:afterGc-before,workerHeap,browser:browser.version()});
        await page.evaluate(()=>globalThis.bridge.close());
    }finally{await browser.close();}
}
fs.writeFileSync(config.output,JSON.stringify({description:'Engine-only initialization; no raster and no Kotlin application. RSS includes runtime/code/shared mappings and uncollected temporary allocations. Browser RSS sums renderer processes; shared pages can be counted twice.',platform:process.platform,arch:process.arch,node:process.version,nodeRuns,browserRuns},null,2)+'\n');
