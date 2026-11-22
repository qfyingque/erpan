import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';
import crypto from 'node:crypto';
const code = fs.readFileSync(new URL('../operit/phone10-mobile-voice.js', import.meta.url), 'utf8');
function fixture() {
  const files = new Map(), loaded = [], closed = [];
  const state = { failCopy: false, copies: 0 };
  let sequence = 0;
  const key = f => typeof f === 'string' ? f : f.getAbsolutePath();
  const file = p => ({getAbsolutePath:()=>p, exists:()=>files.has(p), setReadOnly:()=>true,
    renameTo(target) { files.set(key(target), files.get(p)); files.delete(p); return true; }});
  const input = bytes => {
    let pos=0;
    return { read:()=>pos < bytes.length ? bytes[pos++] : -1, close:()=>closed.push('input') };
  };
  const workerBytes = Buffer.from('test worker content'.repeat(30));
  const hash = crypto.createHash('sha256').update(workerBytes).digest('hex');
  const context = { getFilesDir:()=>'/files', getDir:()=>'/worker', createPackageContext:()=>({getAssets:()=>({
    open:name=>name.endsWith('.sha256') ? {hash,close(){}} : input(workerBytes)
  })}) };
  const worker = {};
  const sandbox = {exports:{},Java:{loadJar:p=>{assert.ok(files.get(p)?.length);loaded.push(p);},type(name){
    switch(name) {
      case 'java.io.File': return {newInstance:(parent,name)=>file(key(parent)+'/'+name)};
      case 'java.util.UUID': return {randomUUID:()=>String(++sequence)};
      case 'java.util.Scanner': return {newInstance:s=>({next:()=>s.hash,close(){}})};
      case 'java.util.Base64': return {getDecoder:()=>({decode:s=>Buffer.from(s,'base64')})};
      case 'java.io.ByteArrayInputStream': return {newInstance:input};
      case 'java.io.FileInputStream': return {newInstance:f=>input(files.get(key(f)))};
      case 'java.nio.channels.Channels': return {newChannel:s=>({read:()=>s.read()})};
      case 'java.io.FileOutputStream': return {newInstance:f=>{
        const p=key(f);files.set(p,Buffer.alloc(0));
        return {close:()=>closed.push('output'),getChannel:()=>({transferFrom(src,offset,count){
          state.copies++;
          if(state.failCopy) { state.failCopy=false; throw Error('interrupted copy'); }
          const bytes=[];
          for(let i=0;i<Math.min(count,97);i++){const b=src.read();if(b===-1)break;bytes.push(b);}
          assert.equal(offset,files.get(p).length);
          files.set(p,Buffer.concat([files.get(p),Buffer.from(bytes)]));return bytes.length;
        }})};
      }};
      case 'java.security.MessageDigest': return {getInstance:()=>{
        const h=crypto.createHash('sha256');return {update:b=>h.update(Buffer.from([b])),digest:()=>Array.from(h.digest())};
      }};
      case 'java.security.DigestInputStream': return {newInstance:(s,h)=>({read(){const b=s.read();if(b!==-1)h.update(b);return b;},close:()=>s.close()})};
      case 'com.huigu.phone10.mobile.OperitRequestWorker': return {newInstance:()=>worker};
      default: throw Error('unexpected Java class '+name);
    }
  }}};
  vm.runInNewContext(code,sandbox);
  return {sandbox,context,files,loaded,closed,state,worker,workerBytes};
}
test('Android 12 worker copies all bytes without InputStream.transferTo',()=>{
  const f=fixture();assert.equal(f.sandbox.loadWorker(f.context),f.worker);
  assert.deepEqual(f.files.get(f.loaded[0]),f.workerBytes);
  assert.ok(f.state.copies>1);assert.deepEqual(f.closed,['input','output']);
  const copies=f.state.copies;f.sandbox.loadWorker(f.context);assert.equal(f.state.copies,copies);
});
test('Android 12 history ignores the empty file left by the old script',()=>{
  const f=fixture();const old='/files/phone10-native-read-8799234c1d79d892.jar';
  f.files.set(old,Buffer.alloc(0));f.sandbox.ensureNativeHistory(f.context);
  assert.notEqual(f.loaded[0],old);assert.equal(f.files.get(old).length,0);
  assert.equal(crypto.createHash('sha256').update(f.files.get(f.loaded[0])).digest('hex'),
    crypto.createHash('sha256').update(fs.readFileSync(new URL('../operit/native/native-read.jar',import.meta.url))).digest('hex'));
  f.sandbox.ensureNativeHistory(f.context);assert.equal(f.loaded.length,1);
});
test('interrupted history copy does not poison the next attempt',()=>{
  const f=fixture();f.state.failCopy=true;
  assert.throws(()=>f.sandbox.ensureNativeHistory(f.context));assert.equal(f.loaded.length,0);
  assert.deepEqual(f.closed,['input','output']);
  f.sandbox.ensureNativeHistory(f.context);assert.equal(f.loaded.length,1);
});
test('interrupted worker copy can retry without loading partial bytes',()=>{
  const f=fixture();f.state.failCopy=true;
  assert.throws(()=>f.sandbox.loadWorker(f.context),/WORKER_LOAD/);assert.equal(f.loaded.length,0);
  f.sandbox.loadWorker(f.context);assert.deepEqual(f.files.get(f.loaded[0]),f.workerBytes);
});
