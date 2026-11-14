// Demo-only launcher. Do not inherit ADC, Firebase tokens, service endpoints,
// function parameters or application secrets from the invoking shell.
const {spawn} = require('node:child_process');
const {mkdirSync} = require('node:fs');
const path = require('node:path');
const root = path.resolve(__dirname, '..');
const mode = process.argv[2];
if (!['test', 'start'].includes(mode) || process.argv.length !== 3) {
  throw new Error('Use test or start; project and endpoints cannot be overridden.');
}
if (process.versions.node.split('.')[0] !== '22') throw new Error('Use Node.js 22.');
const env = {};
for (const key of ['PATH', 'Path', 'SystemRoot', 'SYSTEMROOT', 'windir', 'WINDIR',
  'SystemDrive', 'ComSpec', 'COMSPEC', 'PATHEXT', 'JAVA_HOME']) {
  if (process.env[key]) env[key] = process.env[key];
}
const local = path.join(root, '.firebase');
mkdirSync(path.join(local, 'tmp'), {recursive:true});
Object.assign(env, {CI:'true', NO_UPDATE_NOTIFIER:'1', FUNCTIONS_DISCOVERY_TIMEOUT:'60',
  TEMP:path.join(local, 'tmp'), TMP:path.join(local, 'tmp'),
  XDG_CONFIG_HOME:path.join(local, 'config'),
  FIREBASE_EMULATORS_PATH:path.join(local, 'emulators')});
const args = [path.join(root, 'node_modules/firebase-tools/lib/bin/firebase.js'),
  mode === 'test' ? 'emulators:exec' : 'emulators:start',
  '--project', 'demo-trailo', '--only', 'auth,firestore,functions'];
if (mode === 'test') args.push('node --test --test-concurrency=1 functions/test/*.test.cjs');
const child = spawn(process.execPath, args, {cwd:root, env, stdio:'inherit'});
child.on('error', () => { console.error('Unable to start local Firebase tooling.'); process.exitCode=1; });
child.on('exit', code => { process.exitCode=code ?? 1; });
