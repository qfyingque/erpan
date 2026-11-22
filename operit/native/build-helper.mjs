import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import {fileURLToPath} from 'node:url';
import {spawnSync} from 'node:child_process';
const dir=path.dirname(fileURLToPath(import.meta.url));
const build=path.resolve(dir,'../../build/nativeHistory');
const classes=path.join(build,'classes'),tests=path.join(build,'tests');
const {JAVA_HOME,ANDROID_HOME,KOTLIN_STDLIB}=process.env;
if(!JAVA_HOME||!ANDROID_HOME||!KOTLIN_STDLIB)throw Error('Set JAVA_HOME, ANDROID_HOME and KOTLIN_STDLIB (existing Kotlin stdlib jar)');
const bin=name=>path.join(JAVA_HOME,'bin',name+(process.platform==='win32'?'.exe':''));
function run(exe,args){const r=spawnSync(exe,args,{stdio:'inherit'});if(r.error)throw r.error;if(r.status!==0)throw Error('Build failed: '+exe);}
fs.mkdirSync(classes,{recursive:true});fs.mkdirSync(tests,{recursive:true});
run(bin('javac'),['--release','8','-encoding','UTF-8','-cp',KOTLIN_STDLIB,'-d',classes,path.join(dir,'NativeHistoryRead.java')]);
run(bin('javac'),['--release','8','-encoding','UTF-8','-cp',[classes,KOTLIN_STDLIB].join(path.delimiter),'-d',tests,path.join(dir,'NativeHistoryReadTest.java')]);
run(bin('java'),['-cp',[tests,classes,KOTLIN_STDLIB].join(path.delimiter),'NativeHistoryReadTest']);
const input=path.join(build,'input.jar'),output=path.join(dir,'native-read.jar');
run(bin('jar'),['cf',input,'-C',classes,'.']);
run(bin('java'),['-cp',path.join(ANDROID_HOME,'build-tools','36.0.0','lib','d8.jar'),'com.android.tools.r8.D8',
 '--min-api','26','--lib',path.join(ANDROID_HOME,'platforms','android-36','android.jar'),
 '--classpath',KOTLIN_STDLIB,'--output',output,input]);
const bytes=fs.readFileSync(output),hash=crypto.createHash('sha256').update(bytes).digest('hex');
const plugin=path.resolve(dir,'../phone10-mobile-voice.js');
let script=fs.readFileSync(plugin,'utf8');
for(const pattern of [/phone10-native-read-[a-f0-9]{16}-compat12\.jar/,/\.getDecoder\(\)\.decode\('[A-Za-z0-9+/=]+'\)/,/actual!=='[a-f0-9]{64}'/])
 if(!pattern.test(script))throw Error('Helper embedding marker missing');
script=script.replace(/phone10-native-read-[a-f0-9]{16}-compat12\.jar/g,`phone10-native-read-${hash.slice(0,16)}-compat12.jar`)
 .replace(/\.getDecoder\(\)\.decode\('[A-Za-z0-9+/=]+'\)/,`.getDecoder().decode('${bytes.toString('base64')}')`)
 .replace(/actual!=='[a-f0-9]{64}'/,`actual!=='${hash}'`);
fs.writeFileSync(plugin,script);
console.log('Embedded native history helper SHA256 '+hash);
