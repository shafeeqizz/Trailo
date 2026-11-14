const {test, before, after, beforeEach} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const {initializeTestEnvironment, assertFails, assertSucceeds} = require('@firebase/rules-unit-testing');
const {doc, getDoc, setDoc, updateDoc, deleteDoc, collection, getDocs} = require('firebase/firestore');
const {getFirestore, Timestamp} = require('firebase-admin/firestore');
const {createSession} = require('../lib/index');
const {issueInvitation, redeemInvitation, consumeAttempt} = require('../lib/invitations');
const {createHash} = require('node:crypto');
const {leaveSession,endSession} = require('../lib/lifecycle');
const {resolveOperation} = require('../lib/operations');
const project = 'demo-trailo';
let env, db;
before(async () => {
  assert.equal(process.env.GCLOUD_PROJECT, project);
  assert.equal(process.env.FIRESTORE_EMULATOR_HOST, "127.0.0.1:8080");
  assert.equal(process.env.FIREBASE_AUTH_EMULATOR_HOST, "127.0.0.1:9099");
  env = await initializeTestEnvironment({projectId:project, firestore:{rules:fs.readFileSync('firestore.rules','utf8')}});
  db = getFirestore();
});
after(async () => { await env?.cleanup(); await db?.terminate(); });
beforeEach(async () => { await env.clearFirestore(); });
async function token() {
  const r = await fetch('http://127.0.0.1:9099/identitytoolkit.googleapis.com/v1/accounts:signUp?key=demo-key', {
    method:'POST', headers:{'Content-Type':'application/json'}, body:JSON.stringify({returnSecureToken:true})});
  assert.equal(r.status,200); return r.json();
}
async function call(data, auth, endpoint = 'createConvoy') {
  const r = await fetch(`http://127.0.0.1:5001/${project}/us-central1/${endpoint}`, {
    method:'POST', headers:{'Content-Type':'application/json', ...(auth?{Authorization:`Bearer ${auth}`}:{})},
    body:JSON.stringify({data})});
  return {status:r.status, body:await r.json()};
}
test('authenticated callable creates metadata, creator membership and private pointer', async () => {
  const user = await token(); const r = await call({name:'  Road trip  '},user.idToken);
  assert.equal(r.status,200); const result=r.body.result;
  assert.match(result.convoyId,/^[a-f0-9]{64}$/);
  const c=(await db.doc(`convoys/${result.convoyId}`).get()).data();
  assert.equal(c.name,'Road trip'); assert.equal(c.creatorId,user.localId); assert.equal(c.leaderId,user.localId);
  assert.equal(c.status,'active'); assert.equal(c.maxMembers,20);
  assert.deepEqual(Object.keys(c.members),[user.localId]);
  assert.ok(c.createdAt instanceof Timestamp); assert.ok(c.members[user.localId].joinedAt instanceof Timestamp);
  assert.ok(Math.abs(c.expiresAt.toMillis()-c.createdAt.toMillis()-86400000)<10000);
  assert.equal((await db.doc(`userSessions/${user.localId}`).get()).get('convoyId'),result.convoyId);
});
test('unauthenticated callable fails', async () => {
  assert.equal((await call({name:'Trip'})).body.error.status,'UNAUTHENTICATED');
});
test('invalid, oversized and forged fields rejected without writes', async () => {
  const user=await token();
  for(const data of [null,{},[],{name:42},{name:''},{name:'   '},{name:'x'.repeat(81)},{name:'a\nb'},{name:'ok',creatorId:'other'}]) {
    assert.equal((await call(data,user.idToken)).body.error.status,'INVALID_ARGUMENT');
  }
  assert.equal((await db.collection('convoys').get()).size,0);
  assert.equal((await db.collection('userSessions').get()).size,0);
});
test('duplicate callable fails with no partial records', async () => {
  const user=await token(); assert.equal((await call({name:'Trip'},user.idToken)).status,200);
  assert.equal((await call({name:'Second'},user.idToken)).body.error.status,'ALREADY_EXISTS');
  assert.equal((await db.collection('convoys').get()).size,1);
});
test('concurrent callable requests create exactly one convoy', async () => {
  const user=await token(); const results=await Promise.all(Array.from({length:5},()=>call({name:'Trip'},user.idToken)));
  assert.equal(results.filter(r=>r.status===200).length,1);
  assert.ok(results.filter(r=>r.status!==200).every(r=>r.body.error.status==='ALREADY_EXISTS'));
  assert.equal((await db.collection('convoys').get()).size,1);
  assert.equal((await db.collection('userSessions').get()).size,1);
});
test('expired pointer replaced at exact expiry; old convoy retained but inaccessible', async () => {
  const start=Date.now()-86400000; const old=await createSession(db,'owner',{name:'Old'},24,()=>start);
  const fresh=await createSession(db,'owner',{name:'New'},24,()=>start+86400000);
  assert.notEqual(fresh.convoyId,old.convoyId);
  assert.equal((await db.doc('userSessions/owner').get()).get('convoyId'),fresh.convoyId);
  await assertFails(getDoc(doc(env.authenticatedContext('owner').firestore(),'convoys',old.convoyId)));
});
test('custom expiry uses server configuration', async () => {
  const now=Date.now(); const r=await createSession(db,'owner',{name:'Trip'},2,()=>now);
  assert.equal(r.expiresAt,now+7200000);
});
test('transaction write failure rolls back convoy and pointer', async () => {
  // Inject an error after staging both writes inside a real emulator transaction.
  const failingDb = {collection: (...args) => db.collection(...args),
    runTransaction: callback => db.runTransaction(async tx => {
      await callback(tx); throw new Error('Injected transaction failure');
    })};
  await assert.rejects(createSession(failingDb,'owner',{name:'Trip'}), /Injected transaction failure/);
  assert.equal((await db.collection('convoys').get()).size,0);
  assert.equal((await db.collection('userSessions').get()).size,0);
});
test('rules isolate active members, private pointers, lists and all writes', async () => {
  const r=await createSession(db,'owner',{name:'Trip'});
  const owner=env.authenticatedContext('owner').firestore();
  const other=env.authenticatedContext('other').firestore();
  const anon=env.unauthenticatedContext().firestore();
  await assertSucceeds(getDoc(doc(owner,'convoys',r.convoyId)));
  await assertSucceeds(getDoc(doc(owner,'userSessions','owner')));
  for(const client of [other,anon]) {
    await assertFails(getDoc(doc(client,'convoys',r.convoyId)));
    await assertFails(getDoc(doc(client,'userSessions','owner')));
  }
  for(const client of [owner,other,anon]) {
    for(const path of [`convoys/${r.convoyId}`,'userSessions/owner','invitations/secret','rateLimits/key','unknown/key']) {
      await assertFails(setDoc(doc(client,path),{members:{other:{}}}));
      await assertFails(deleteDoc(doc(client,path)));
    }
    await assertFails(getDocs(collection(client,'convoys')));
    await assertFails(getDocs(collection(client,'userSessions')));
    await assertFails(getDoc(doc(client,'invitations/secret')));
  }
  await assertFails(updateDoc(doc(owner,'convoys',r.convoyId),{leaderId:'other'}));
  await db.doc(`convoys/${r.convoyId}`).update({members:{}});
  await assertFails(getDoc(doc(owner,'convoys',r.convoyId)));
});
test('ended convoys are unreadable and stale pointers can be replaced', async () => {
  const old=await createSession(db,'owner',{name:'Old'});
  await db.doc(`convoys/${old.convoyId}`).update({status:'ended'});
  await assertFails(getDoc(doc(env.authenticatedContext('owner').firestore(),'convoys',old.convoyId)));
  const fresh=await createSession(db,'owner',{name:'New'});
  assert.notEqual(fresh.convoyId,old.convoyId);
});

test('malformed pointer fails closed without partial records', async () => {
  await db.doc('userSessions/owner').set({convoyId:'bad',expiresAt:'invalid'});
  await assert.rejects(createSession(db,'owner',{name:'Trip'}),{code:'failed-precondition'});
  assert.equal((await db.collection('convoys').get()).size,0);
});
test('missing target pointer is replaced', async () => {
  await db.doc('userSessions/owner').set({convoyId:'missing',expiresAt:Timestamp.fromMillis(Date.now()+86400000)});
  const fresh=await createSession(db,'owner',{name:'Trip'});
  assert.equal((await db.doc('userSessions/owner').get()).get('convoyId'),fresh.convoyId);
});



const invitationHash = secret => createHash('sha256').update(secret).digest('hex');
async function fixture(owner='owner') {
  const convoy = await createSession(db,owner,{name:'Trip'});
  const invitation = await issueInvitation(db,owner,{convoyId:convoy.convoyId});
  return {...convoy,...invitation};
}
function failMutation(db) {
  // Leave the attempt accounting transaction intact, fail the following mutation.
  let calls=0;
  return {collection: (...args)=>db.collection(...args), runTransaction: callback=>{
    calls++;
    const fail=calls===2;
    return db.runTransaction(async tx=>{const result=await callback(tx);
      if(fail) throw new Error('Injected transaction failure'); return result;});
  }};
}
test('creator callable issues hash-only invitation; authenticated user redeems and obtains read access',async()=>{
  const owner=await token(), member=await token();
  const created=await call({name:'Trip'},owner.idToken);
  const convoyId=created.body.result.convoyId;
  const issued=await call({convoyId},owner.idToken,'createConvoyInvitation');
  assert.equal(issued.status,200);
  const invite=issued.body.result;
  assert.ok(/^[A-Z2-7]{25}[AEIMQUY4]$/.test(invite.secret));
  const records=await db.collection('invitations').get(); assert.equal(records.size,1);
  const stored=records.docs[0].data();
  assert.ok(records.docs[0].id===invitationHash(invite.secret));
  assert.deepEqual(Object.keys(stored).sort(),['convoyId','createdAt','expiresAt','version']);
  assert.equal(stored.convoyId,convoyId); assert.equal(stored.version,1);
  assert.ok(stored.createdAt instanceof Timestamp);
  assert.ok(Math.abs(stored.expiresAt.toMillis()-stored.createdAt.toMillis()-1800000)<10000);
  const joined=await call({secret:invite.secret.toLowerCase()},member.idToken,'joinConvoy');
  assert.equal(joined.status,200); assert.equal(joined.body.result.convoyId,convoyId);
  const c=(await db.doc(`convoys/${convoyId}`).get()).data();
  assert.equal(Object.keys(c.members).length,2); assert.ok(c.members[member.localId].joinedAt instanceof Timestamp);
  const pointer=(await db.doc(`userSessions/${member.localId}`).get()).data();
  assert.equal(pointer.convoyId,convoyId); assert.equal(pointer.expiresAt.toMillis(),c.expiresAt.toMillis());
  await assertSucceeds(getDoc(doc(env.authenticatedContext(member.localId).firestore(),'convoys',convoyId)));
});
test('invitation issuance requires auth and active creator membership',async()=>{
  const f=await fixture();
  const stranger=await token();
  assert.equal((await call({convoyId:f.convoyId},undefined,'createConvoyInvitation')).body.error.status,'UNAUTHENTICATED');
  assert.equal((await call({convoyId:f.convoyId},stranger.idToken,'createConvoyInvitation')).body.error.status,'PERMISSION_DENIED');
  await redeemInvitation(db,'member',{secret:f.secret});
  await assert.rejects(issueInvitation(db,'member',{convoyId:f.convoyId}),{code:'permission-denied'});
  await db.doc(`convoys/${f.convoyId}`).update({members:{}});
  await assert.rejects(issueInvitation(db,'owner',{convoyId:f.convoyId}),{code:'permission-denied'});
  assert.equal((await db.collection('invitations').get()).size,1);
});
test('ended and expired convoys cannot issue invitations; invitation expiry is capped and configurable',async()=>{
  const start=Date.now();
  const c=await createSession(db,'owner',{name:'Trip'},1,()=>start);
  const nearEnd=await issueInvitation(db,'owner',{convoyId:c.convoyId},30,()=>start+3500000);
  assert.equal(nearEnd.expiresAt,c.expiresAt);
  const short=await issueInvitation(db,'owner',{convoyId:c.convoyId},2,()=>start);
  assert.equal(short.expiresAt,start+120000);
  await assert.rejects(issueInvitation(db,'owner',{convoyId:c.convoyId},30,()=>c.expiresAt),{code:'permission-denied'});
  await db.doc(`convoys/${c.convoyId}`).update({status:'ended'});
  await assert.rejects(issueInvitation(db,'owner',{convoyId:c.convoyId}),{code:'permission-denied'});
});
test('invalid, expired, revoked, missing-target and ended invitations share one generic error',async()=>{
  const user=await token(); const f=await fixture();
  const responses=[];
  responses.push((await call({secret:'A'.repeat(26)},user.idToken,'joinConvoy')).body.error);
  await db.doc(`invitations/${invitationHash(f.secret)}`).update({expiresAt:Timestamp.fromMillis(0)});
  responses.push((await call({secret:f.secret},user.idToken,'joinConvoy')).body.error);
  const rotated=await issueInvitation(db,'owner',{convoyId:f.convoyId});
  await db.doc(`invitations/${invitationHash(f.secret)}`).update({expiresAt:Timestamp.fromMillis(Date.now()+60000)});
  responses.push((await call({secret:f.secret},user.idToken,'joinConvoy')).body.error);
  await db.doc(`convoys/${f.convoyId}`).update({status:'ended'});
  responses.push((await call({secret:rotated.secret},user.idToken,'joinConvoy')).body.error);
  await db.doc(`convoys/${f.convoyId}`).update({status:'active',expiresAt:Timestamp.fromMillis(0)});
  responses.push((await call({secret:rotated.secret},user.idToken,'joinConvoy')).body.error);
  await db.doc(`convoys/${f.convoyId}`).delete();
  responses.push((await call({secret:rotated.secret},user.idToken,'joinConvoy')).body.error);
  assert.ok(responses.every(e=>e.status==='FAILED_PRECONDITION' && e.message==='Invitation is invalid or unavailable.'));
  assert.equal((await db.doc(`userSessions/${user.localId}`).get()).exists,false);
});
test('rotation revokes prior secret and keeps plaintext out of stored documents',async()=>{
  const f=await fixture(); const next=await issueInvitation(db,'owner',{convoyId:f.convoyId});
  assert.ok(next.secret!==f.secret);
  await assert.rejects(redeemInvitation(db,'member',{secret:f.secret}),{code:'failed-precondition'});
  await redeemInvitation(db,'member',{secret:next.secret});
  assert.equal((await db.doc(`convoys/${f.convoyId}`).get()).get('inviteVersion'),2);
  for(const name of ['convoys','invitations','userSessions','rateLimits']) {
    const records=await db.collection(name).get();
    assert.ok(records.docs.every(d=>!JSON.stringify(d.data()).includes(next.secret)));
  }
});
test('duplicate joins are idempotent and repair missing or stale matching pointers',async()=>{
  const f=await fixture();
  await redeemInvitation(db,'member',{secret:f.secret});
  const original=(await db.doc(`convoys/${f.convoyId}`).get()).get('members.member.joinedAt').toMillis();
  await redeemInvitation(db,'member',{secret:f.secret});
  await db.doc('userSessions/member').delete();
  await redeemInvitation(db,'member',{secret:f.secret});
  await db.doc('userSessions/member').set({convoyId:'stale',expiresAt:Timestamp.fromMillis(0)});
  await redeemInvitation(db,'member',{secret:f.secret});
  const c=await db.doc(`convoys/${f.convoyId}`).get();
  assert.equal(Object.keys(c.get('members')).length,2);
  assert.equal(c.get('members.member.joinedAt').toMillis(),original);
  assert.equal((await db.doc('userSessions/member').get()).get('convoyId'),f.convoyId);
});
test('joining another active convoy is rejected without altering either roster or pointer',async()=>{
  const f=await fixture(); const own=await createSession(db,'member',{name:'Other'});
  await assert.rejects(redeemInvitation(db,'member',{secret:f.secret}),{code:'already-exists'});
  assert.equal((await db.doc('userSessions/member').get()).get('convoyId'),own.convoyId);
  assert.equal(Object.keys((await db.doc(`convoys/${f.convoyId}`).get()).get('members')).length,1);
});
test('parallel callable joins serialize the final slot at 20 members',async()=>{
  const f=await fixture(); const ref=db.doc(`convoys/${f.convoyId}`);
  const members=(await ref.get()).get('members');
  for(let i=0;i<18;i++)members[`seed${i}`]={joinedAt:Timestamp.now()};
  await ref.update({members});
  const users=await Promise.all([token(),token(),token()]);
  const responses=await Promise.all(users.map(u=>call({secret:f.secret},u.idToken,'joinConvoy')));
  assert.equal(responses.filter(r=>r.status===200).length,1);
  assert.equal(responses.filter(r=>r.body.error?.status==='RESOURCE_EXHAUSTED').length,2);
  const c=await ref.get(); assert.equal(Object.keys(c.get('members')).length,20);
  for(let i=0;i<users.length;i++) {
    assert.equal((await db.doc(`userSessions/${users[i].localId}`).get()).exists,responses[i].status===200);
  }
  // Existing membership remains idempotent even when full.
  const winner=users[responses.findIndex(r=>r.status===200)];
  assert.equal((await call({secret:f.secret},winner.idToken,'joinConvoy')).status,200);
});
test('concurrent joins to different convoys preserve one active session',async()=>{
  const a=await fixture('a'), b=await fixture('b');
  const results=await Promise.allSettled([redeemInvitation(db,'member',{secret:a.secret}),redeemInvitation(db,'member',{secret:b.secret})]);
  assert.equal(results.filter(r=>r.status==='fulfilled').length,1);
  assert.equal(results.find(r=>r.status==='rejected').reason.code,'already-exists');
  const pointer=(await db.doc('userSessions/member').get()).get('convoyId');
  for(const f of [a,b])assert.equal(Object.hasOwn((await db.doc(`convoys/${f.convoyId}`).get()).get('members'),'member'),pointer===f.convoyId);
});
test('concurrent create versus join preserves one active session',async()=>{
  const f=await fixture();
  const results=await Promise.allSettled([createSession(db,'member',{name:'Other'}),redeemInvitation(db,'member',{secret:f.secret})]);
  assert.equal(results.filter(r=>r.status==='fulfilled').length,1);
  assert.equal(results.find(r=>r.status==='rejected').reason.code,'already-exists');
  assert.equal((await db.collection('convoys').get()).size,results[0].status==='fulfilled'?2:1);
});
test('concurrent duplicate joins do not duplicate membership',async()=>{
  const f=await fixture();
  const results=await Promise.all(Array.from({length:3},()=>redeemInvitation(db,'member',{secret:f.secret})));
  assert.ok(results.every(r=>r.convoyId===f.convoyId));
  assert.equal(Object.keys((await db.doc(`convoys/${f.convoyId}`).get()).get('members')).length,2);
});
test('invitation creation rollback preserves current version and creates no new invitation',async()=>{
  const f=await fixture();
  await assert.rejects(issueInvitation(failMutation(db),'owner',{convoyId:f.convoyId}),/Injected transaction failure/);
  assert.equal((await db.doc(`convoys/${f.convoyId}`).get()).get('inviteVersion'),1);
  assert.equal((await db.collection('invitations').get()).size,1);
  await redeemInvitation(db,'member',{secret:f.secret});
});
test('join rollback preserves roster and existing expired pointer; attempt still counted',async()=>{
  const f=await fixture(); const old={convoyId:'old',expiresAt:Timestamp.fromMillis(0)};
  await db.doc('userSessions/member').set(old);
  await assert.rejects(redeemInvitation(failMutation(db),'member',{secret:f.secret}),/Injected transaction failure/);
  assert.deepEqual((await db.doc('userSessions/member').get()).data(),old);
  assert.equal(Object.keys((await db.doc(`convoys/${f.convoyId}`).get()).get('members')).length,1);
  const attempts=await db.collection('rateLimits').get();
  assert.ok(attempts.docs.some(d=>d.id.startsWith('join_')&&d.get('count')===1));
});
test('real invitation hashes, rate-limit documents and pointers remain private',async()=>{
  const f=await fixture(); await redeemInvitation(db,'member',{secret:f.secret});
  const rates=await db.collection('rateLimits').get();
  for(const uid of ['owner','member','stranger',null]) {
    const client=uid?env.authenticatedContext(uid).firestore():env.unauthenticatedContext().firestore();
    for(const path of [`invitations/${invitationHash(f.secret)}`,`rateLimits/${rates.docs[0].id}`]) {
      await assertFails(getDoc(doc(client,path))); await assertFails(setDoc(doc(client,path),{}));
      await assertFails(deleteDoc(doc(client,path)));
    }
    await assertFails(getDocs(collection(client,'invitations')));
    await assertFails(getDocs(collection(client,'rateLimits')));
    if(uid!=='member')await assertFails(getDoc(doc(client,'userSessions','member')));
    await assertFails(updateDoc(doc(client,'convoys',f.convoyId),{members:{}}));
  }
});
test('schema validation, malformed codes and invalid credentials are rejected',async()=>{
  const user=await token();
  assert.equal((await call({secret:'bad'},undefined,'joinConvoy')).body.error.status,'UNAUTHENTICATED');
  assert.equal((await call({secret:'bad'},'invalid-token','joinConvoy')).body.error.status,'UNAUTHENTICATED');
  for(const data of [{},{secret:42},{secret:'A'.repeat(1000)},{secret:'A'.repeat(25)+'B'},{secret:'bad',uid:'forged'},null]) {
    const response=await call(data,user.idToken,'joinConvoy');
    assert.ok(['INVALID_ARGUMENT','FAILED_PRECONDITION'].includes(response.body.error.status));
  }
  assert.equal((await call({convoyId:'bad'},user.idToken,'createConvoyInvitation')).body.error.status,'INVALID_ARGUMENT');
  assert.equal((await db.collection('userSessions').get()).size,0);
});
test('failed callable attempts count toward rate limit and windows reset at boundary',async()=>{
  const user=await token();
  for(let i=0;i<10;i++)assert.equal((await call({secret:'bad'},user.idToken,'joinConvoy')).body.error.status,'FAILED_PRECONDITION');
  const blocked=await call({secret:'bad'},user.idToken,'joinConvoy');
  assert.equal(blocked.body.error.status,'RESOURCE_EXHAUSTED');
  const rates=await db.collection('rateLimits').get(); assert.equal(rates.size,1); assert.equal(rates.docs[0].get('count'),10);
  assert.equal((await db.collection('userSessions').get()).size,0);
  const start=Date.now();
  for(let i=0;i<10;i++)await consumeAttempt(db,'clock','join',()=>start);
  await assert.rejects(consumeAttempt(db,'clock','join',()=>start+59999),{code:'resource-exhausted'});
  await consumeAttempt(db,'clock','join',()=>start+60000);
});
test('attempt limits serialize concurrently and issuance has its own budget',async()=>{
  const time=Date.now();
  const attempts=await Promise.allSettled(Array.from({length:12},()=>consumeAttempt(db,'burst','join',()=>time)));
  assert.equal(attempts.filter(r=>r.status==='fulfilled').length,10);
  assert.ok(attempts.filter(r=>r.status==='rejected').every(r=>r.reason.code==='resource-exhausted'));
  await consumeAttempt(db,'burst','invite',()=>time);
});
test('grouped codes normalize strictly; malformed live pointers fail closed',async()=>{
  const f=await fixture(); const grouped=f.secret.slice(0,20).match(/.{5}/g).join('-')+'-'+f.secret.slice(20);
  await redeemInvitation(db,'grouped',{secret:' '+grouped.toLowerCase()+' '});
  await db.doc('userSessions/broken').set({convoyId:'bad',expiresAt:'invalid'});
  await assert.rejects(redeemInvitation(db,'broken',{secret:f.secret}),{code:'failed-precondition'});
  assert.equal(Object.hasOwn((await db.doc(`convoys/${f.convoyId}`).get()).get('members'),'broken'),false);
});
test('rotation racing redemption serializes on the convoy version',async()=>{
  const f=await fixture();
  const results=await Promise.allSettled([issueInvitation(db,'owner',{convoyId:f.convoyId}),redeemInvitation(db,'racing',{secret:f.secret})]);
  assert.equal(results[0].status,'fulfilled');
  if(results[1].status==='rejected')assert.equal(results[1].reason.code,'failed-precondition');
  await assert.rejects(redeemInvitation(db,'late',{secret:f.secret}),{code:'failed-precondition'});
  await redeemInvitation(db,'late',{secret:results[0].value.secret});
});
test('expiry is rechecked after pointer reads before joining',async()=>{
  const f=await fixture(); let calls=0;
  const clock=()=>++calls<3?f.expiresAt-1:f.expiresAt;
  await assert.rejects(redeemInvitation(db,'late',{secret:f.secret},clock),{code:'failed-precondition'});
  assert.equal((await db.doc('userSessions/late').get()).exists,false);
  assert.equal(Object.hasOwn((await db.doc(`convoys/${f.convoyId}`).get()).get('members'),'late'),false);
});
test('live pointer without membership fails closed; missing target pointer is replaced',async()=>{
  const f=await fixture();
  await db.doc('userSessions/broken').set({convoyId:f.convoyId,expiresAt:Timestamp.fromMillis(f.expiresAt)});
  await assert.rejects(redeemInvitation(db,'broken',{secret:f.secret}),{code:'failed-precondition'});
  await db.doc('userSessions/stale').set({convoyId:'missing',expiresAt:Timestamp.fromMillis(f.expiresAt)});
  await redeemInvitation(db,'stale',{secret:f.secret});
  assert.equal((await db.doc('userSessions/stale').get()).get('convoyId'),f.convoyId);
});
test('UIDs containing dots are stored as literal roster keys',async()=>{
  const f=await fixture(); await redeemInvitation(db,'member.with.dots',{secret:f.secret});
  const roster=(await db.doc(`convoys/${f.convoyId}`).get()).get('members');
  assert.ok(Object.hasOwn(roster,'member.with.dots')); assert.equal(Object.keys(roster).length,2);
});


test('participant callable leave removes only self and revokes convoy reads immediately',async()=>{
  const owner=await token(), member=await token();
  const f=await fixture(owner.localId);
  await redeemInvitation(db,member.localId,{secret:f.secret});
  await redeemInvitation(db,'remaining',{secret:f.secret});
  const ref=db.doc(`convoys/${f.convoyId}`); const before=(await ref.get()).data();
  const response=await call({convoyId:f.convoyId},member.idToken,'leaveConvoy');
  assert.equal(response.status,200); assert.deepEqual(response.body.result,{left:true});
  const after=(await ref.get()).data(); const expected={...before.members}; delete expected[member.localId];
  assert.deepEqual(after.members,expected); assert.equal(Object.keys(after.members).length,2);
  assert.equal(after.creatorId,before.creatorId); assert.equal(after.leaderId,before.leaderId);
  assert.equal((await db.doc(`userSessions/${member.localId}`).get()).exists,false);
  assert.equal((await db.doc('userSessions/remaining').get()).get('convoyId'),f.convoyId);
  await assertFails(getDoc(doc(env.authenticatedContext(member.localId).firestore(),'convoys',f.convoyId)));
  await assertSucceeds(getDoc(doc(env.authenticatedContext('remaining').firestore(),'convoys',f.convoyId)));
});
test('leave cannot remove another user; nonmember requests are harmless no-ops',async()=>{
  const f=await fixture(); await redeemInvitation(db,'member',{secret:f.secret}); const outsider=await token();
  const before=(await db.doc(`convoys/${f.convoyId}`).get()).data();
  assert.equal((await call({convoyId:f.convoyId,uid:'member'},outsider.idToken,'leaveConvoy')).body.error.status,'INVALID_ARGUMENT');
  assert.equal((await call({convoyId:f.convoyId},outsider.idToken,'leaveConvoy')).status,200);
  assert.deepEqual((await db.doc(`convoys/${f.convoyId}`).get()).data(),before);
  assert.equal((await db.doc('userSessions/member').get()).get('convoyId'),f.convoyId);
});
test('creator cannot leave an active convoy even if its pointer is missing',async()=>{
  const owner=await token(); const f=await fixture(owner.localId); await db.doc(`userSessions/${owner.localId}`).delete();
  assert.equal((await call({convoyId:f.convoyId},owner.idToken,'leaveConvoy')).body.error.status,'FAILED_PRECONDITION');
  assert.equal(Object.keys((await db.doc(`convoys/${f.convoyId}`).get()).get('members')).length,1);
});
test('repeated leave is idempotent and never erases a newer pointer',async()=>{
  const f=await fixture(); await redeemInvitation(db,'member',{secret:f.secret});
  await leaveSession(db,'member',{convoyId:f.convoyId});
  const newer=await createSession(db,'member',{name:'New'});
  await leaveSession(db,'member',{convoyId:f.convoyId});
  assert.equal((await db.doc('userSessions/member').get()).get('convoyId'),newer.convoyId);
  assert.equal(Object.keys((await db.doc(`convoys/${f.convoyId}`).get()).get('members')).length,1);
});
test('creator callable ends convoy, invalidates invitations and clears all matching pointers',async()=>{
  const owner=await token(); const f=await fixture(owner.localId);
  await redeemInvitation(db,'member',{secret:f.secret});
  await db.doc(`convoys/${f.convoyId}`).update({leaderId:'member'});
  const before=(await db.doc(`convoys/${f.convoyId}`).get()).data();
  const response=await call({convoyId:f.convoyId},owner.idToken,'endConvoy');
  assert.equal(response.status,200); assert.deepEqual(response.body.result,{ended:true});
  const after=(await db.doc(`convoys/${f.convoyId}`).get()).data();
  assert.equal(after.status,'ended'); assert.equal(after.inviteVersion,before.inviteVersion+1);
  assert.ok(after.endedAt instanceof Timestamp); assert.ok(after.expiresAt.toMillis()<=Date.now());
  assert.equal(after.creatorId,owner.localId); assert.equal(after.leaderId,'member');
  assert.deepEqual(after.members,before.members);
  assert.equal((await db.doc(`userSessions/${owner.localId}`).get()).exists,false);
  assert.equal((await db.doc('userSessions/member').get()).exists,false);
  await assert.rejects(redeemInvitation(db,'late',{secret:f.secret}),{code:'failed-precondition'});
  await assert.rejects(issueInvitation(db,owner.localId,{convoyId:f.convoyId}),{code:'permission-denied'});
  for(const uid of [owner.localId,'member'])await assertFails(getDoc(doc(env.authenticatedContext(uid).firestore(),'convoys',f.convoyId)));
  await leaveSession(db,'member',{convoyId:f.convoyId});
  assert.deepEqual((await db.doc(`convoys/${f.convoyId}`).get()).get('members'),before.members);
});
test('noncreator including leader cannot end convoy; missing target shares denial',async()=>{
  const user=await token(); const f=await fixture(); await redeemInvitation(db,user.localId,{secret:f.secret});
  await db.doc(`convoys/${f.convoyId}`).update({leaderId:user.localId});
  const before=(await db.doc(`convoys/${f.convoyId}`).get()).data();
  for(const id of [f.convoyId,'a'.repeat(64)])assert.equal((await call({convoyId:id},user.idToken,'endConvoy')).body.error.status,'PERMISSION_DENIED');
  assert.deepEqual((await db.doc(`convoys/${f.convoyId}`).get()).data(),before);
  assert.equal((await db.doc(`userSessions/${user.localId}`).get()).get('convoyId'),f.convoyId);
});
test('repeated end preserves timestamps, version, roster and newer session pointers',async()=>{
  const f=await fixture(); await redeemInvitation(db,'member',{secret:f.secret});
  await endSession(db,'owner',{convoyId:f.convoyId}); const ended=(await db.doc(`convoys/${f.convoyId}`).get()).data();
  const newer=await createSession(db,'owner',{name:'New'});
  const memberNew=await createSession(db,'member',{name:'New member session'});
  await endSession(db,'owner',{convoyId:f.convoyId});
  assert.deepEqual((await db.doc(`convoys/${f.convoyId}`).get()).data(),ended);
  assert.equal((await db.doc('userSessions/owner').get()).get('convoyId'),newer.convoyId);
  assert.equal((await db.doc('userSessions/member').get()).get('convoyId'),memberNew.convoyId);
});
test('end preserves mismatching member pointers during initial cleanup',async()=>{
  const f=await fixture(); await redeemInvitation(db,'member',{secret:f.secret});
  await db.doc('userSessions/member').set({convoyId:'newer',expiresAt:Timestamp.fromMillis(Date.now()+86400000)});
  await endSession(db,'owner',{convoyId:f.convoyId});
  assert.equal((await db.doc('userSessions/member').get()).get('convoyId'),'newer');
});
test('join racing end leaves no active membership pointer to ended convoy',async()=>{
  const f=await fixture();
  const results=await Promise.allSettled([redeemInvitation(db,'racing',{secret:f.secret}),endSession(db,'owner',{convoyId:f.convoyId})]);
  assert.equal(results[1].status,'fulfilled');
  if(results[0].status==='rejected')assert.equal(results[0].reason.code,'failed-precondition');
  const convoy=await db.doc(`convoys/${f.convoyId}`).get(); assert.equal(convoy.get('status'),'ended');
  assert.equal((await db.doc('userSessions/racing').get()).exists,false);
  await assertFails(getDoc(doc(env.authenticatedContext('racing').firestore(),'convoys',f.convoyId)));
  await assert.rejects(redeemInvitation(db,'late',{secret:f.secret}),{code:'failed-precondition'});
});
test('leave racing end preserves a consistent final roster and clears pointers',async()=>{
  const f=await fixture(); await redeemInvitation(db,'member',{secret:f.secret});
  await Promise.all([leaveSession(db,'member',{convoyId:f.convoyId}),endSession(db,'owner',{convoyId:f.convoyId})]);
  const ended=await db.doc(`convoys/${f.convoyId}`).get(); assert.equal(ended.get('status'),'ended');
  assert.ok([1,2].includes(Object.keys(ended.get('members')).length));
  assert.equal((await db.doc('userSessions/member').get()).exists,false);
  assert.equal((await db.doc('userSessions/owner').get()).exists,false);
  const frozen=ended.get('members'); await leaveSession(db,'member',{convoyId:f.convoyId});
  assert.deepEqual((await db.doc(`convoys/${f.convoyId}`).get()).get('members'),frozen);
});
test('concurrent leaves preserve remaining members and accurate derived count',async()=>{
  const f=await fixture(); for(const uid of ['a','b','c'])await redeemInvitation(db,uid,{secret:f.secret});
  await Promise.all(['a','b'].map(uid=>leaveSession(db,uid,{convoyId:f.convoyId})));
  assert.deepEqual(Object.keys((await db.doc(`convoys/${f.convoyId}`).get()).get('members')).sort(),['c','owner']);
  assert.equal((await db.doc('userSessions/c').get()).get('convoyId'),f.convoyId);
});
test('join racing leave is serializable and does not lose other members',async()=>{
  const f=await fixture(); await redeemInvitation(db,'member',{secret:f.secret});
  await Promise.all([leaveSession(db,'member',{convoyId:f.convoyId}),redeemInvitation(db,'member',{secret:f.secret})]);
  const roster=(await db.doc(`convoys/${f.convoyId}`).get()).get('members');
  const pointer=await db.doc('userSessions/member').get();
  assert.equal(Object.hasOwn(roster,'member'),pointer.exists); assert.ok(Object.hasOwn(roster,'owner'));
  if(pointer.exists)assert.equal(pointer.get('convoyId'),f.convoyId);
});
test('concurrent repeated end is idempotent',async()=>{
  const f=await fixture(); const version=(await db.doc(`convoys/${f.convoyId}`).get()).get('inviteVersion');
  await Promise.all(Array.from({length:3},()=>endSession(db,'owner',{convoyId:f.convoyId})));
  assert.equal((await db.doc(`convoys/${f.convoyId}`).get()).get('inviteVersion'),version+1);
  assert.equal((await db.doc('userSessions/owner').get()).exists,false);
});
test('expired, ended and missing sessions do not block future creation or joining',async()=>{
  const f=await fixture(); await redeemInvitation(db,'member',{secret:f.secret});
  await db.doc(`convoys/${f.convoyId}`).update({expiresAt:Timestamp.fromMillis(0)});
  const before=(await db.doc(`convoys/${f.convoyId}`).get()).get('members');
  await leaveSession(db,'member',{convoyId:f.convoyId});
  assert.deepEqual((await db.doc(`convoys/${f.convoyId}`).get()).get('members'),before);
  const next=await createSession(db,'member',{name:'Next'});
  await endSession(db,'owner',{convoyId:f.convoyId});
  assert.equal((await db.doc('userSessions/member').get()).get('convoyId'),next.convoyId);
  const other=await fixture('other'); await redeemInvitation(db,'owner',{secret:other.secret});
  assert.equal((await db.doc('userSessions/owner').get()).get('convoyId'),other.convoyId);
  await db.doc('userSessions/stale').set({convoyId:'b'.repeat(64),expiresAt:Timestamp.fromMillis(Date.now()+86400000)});
  await leaveSession(db,'stale',{convoyId:'b'.repeat(64)});
  await redeemInvitation(db,'stale',{secret:other.secret});
});
test('stale live pointer targeting ended session is replaceable without cleanup',async()=>{
  const f=await fixture(); await endSession(db,'owner',{convoyId:f.convoyId});
  await db.doc('userSessions/owner').set({convoyId:f.convoyId,expiresAt:Timestamp.fromMillis(Date.now()+86400000)});
  const next=await createSession(db,'owner',{name:'Next'});
  assert.equal((await db.doc('userSessions/owner').get()).get('convoyId'),next.convoyId);
});
function failingLifecycleDb() {
  return {collection:(...args)=>db.collection(...args),runTransaction:callback=>db.runTransaction(async tx=>{
    await callback(tx); throw new Error('Injected lifecycle failure');
  })};
}
test('failed leave transaction preserves roster and pointer atomically',async()=>{
  const f=await fixture(); await redeemInvitation(db,'member',{secret:f.secret});
  const before=(await db.doc(`convoys/${f.convoyId}`).get()).data();
  await assert.rejects(leaveSession(failingLifecycleDb(),'member',{convoyId:f.convoyId}),/Injected lifecycle failure/);
  assert.deepEqual((await db.doc(`convoys/${f.convoyId}`).get()).data(),before);
  assert.equal((await db.doc('userSessions/member').get()).get('convoyId'),f.convoyId);
});
test('failed end transaction preserves status, invitations and all pointers atomically',async()=>{
  const f=await fixture(); await redeemInvitation(db,'member',{secret:f.secret});
  const before=(await db.doc(`convoys/${f.convoyId}`).get()).data();
  await assert.rejects(endSession(failingLifecycleDb(),'owner',{convoyId:f.convoyId}),/Injected lifecycle failure/);
  assert.deepEqual((await db.doc(`convoys/${f.convoyId}`).get()).data(),before);
  for(const uid of ['owner','member'])assert.equal((await db.doc(`userSessions/${uid}`).get()).get('convoyId'),f.convoyId);
  await redeemInvitation(db,'late',{secret:f.secret});
});
test('lifecycle callables require authentication and reject forged or malformed fields',async()=>{
  const user=await token(); const f=await fixture();
  for(const endpoint of ['leaveConvoy','endConvoy']) {
    assert.equal((await call({convoyId:f.convoyId},undefined,endpoint)).body.error.status,'UNAUTHENTICATED');
    for(const data of [null,{},[],{convoyId:42},{convoyId:'bad'},{convoyId:f.convoyId,uid:'owner'}]) {
      assert.equal((await call(data,user.idToken,endpoint)).body.error.status,'INVALID_ARGUMENT');
    }
  }
});
test('lifecycle maintains cross-convoy isolation and denies direct client writes',async()=>{
  const a=await fixture('a'),b=await fixture('b'); await redeemInvitation(db,'member',{secret:a.secret});
  const before=(await db.doc(`convoys/${b.convoyId}`).get()).data();
  await leaveSession(db,'member',{convoyId:b.convoyId});
  assert.equal((await db.doc('userSessions/member').get()).get('convoyId'),a.convoyId);
  await assert.rejects(endSession(db,'a',{convoyId:b.convoyId}),{code:'permission-denied'});
  assert.deepEqual((await db.doc(`convoys/${b.convoyId}`).get()).data(),before);
  for(const uid of ['a','member','outsider']) {
    const client=env.authenticatedContext(uid).firestore();
    await assertFails(updateDoc(doc(client,'convoys',a.convoyId),{status:'ended'}));
    await assertFails(updateDoc(doc(client,'convoys',a.convoyId),{members:{}}));
    await assertFails(deleteDoc(doc(client,'userSessions','member')));
  }
  await leaveSession(db,'member',{convoyId:a.convoyId});
  await assertFails(getDoc(doc(env.authenticatedContext('member').firestore(),'convoys',a.convoyId)));
  await endSession(db,'a',{convoyId:a.convoyId});
  await assertFails(getDoc(doc(env.authenticatedContext('a').firestore(),'convoys',a.convoyId)));
  await assertSucceeds(getDoc(doc(env.authenticatedContext('b').firestore(),'convoys',b.convoyId)));
});
test('ending a full convoy clears all twenty matching member pointers',async()=>{
  const f=await fixture(); const ref=db.doc(`convoys/${f.convoyId}`);
  const roster=(await ref.get()).get('members'); const batch=db.batch();
  for(let i=0;i<19;i++) {
    const uid=`seed${i}`; roster[uid]={joinedAt:Timestamp.now()};
    batch.set(db.doc(`userSessions/${uid}`),{convoyId:f.convoyId,expiresAt:Timestamp.fromMillis(f.expiresAt)});
  }
  batch.update(ref,{members:roster}); await batch.commit();
  await endSession(db,'owner',{convoyId:f.convoyId});
  assert.equal((await db.collection('userSessions').get()).size,0);
  assert.equal(Object.keys((await ref.get()).get('members')).length,20);
});
test('invitation issuance racing end leaves every code unusable',async()=>{
  const f=await fixture();
  const results=await Promise.allSettled([issueInvitation(db,'owner',{convoyId:f.convoyId}),endSession(db,'owner',{convoyId:f.convoyId})]);
  assert.equal(results[1].status,'fulfilled');
  if(results[0].status==='fulfilled')await assert.rejects(redeemInvitation(db,'later',{secret:results[0].value.secret}),{code:'failed-precondition'});
  else assert.equal(results[0].reason.code,'permission-denied');
  await assert.rejects(redeemInvitation(db,'late',{secret:f.secret}),{code:'failed-precondition'});
});
test('ending an expired convoy racing new creation preserves the new member pointer',async()=>{
  const f=await fixture(); await redeemInvitation(db,'member',{secret:f.secret});
  await db.doc(`convoys/${f.convoyId}`).update({expiresAt:Timestamp.fromMillis(0)});
  const [next]=await Promise.all([createSession(db,'member',{name:'New'}),endSession(db,'owner',{convoyId:f.convoyId})]);
  assert.equal((await db.doc('userSessions/member').get()).get('convoyId'),next.convoyId);
  assert.equal((await db.doc(`convoys/${f.convoyId}`).get()).get('status'),'ended');
});

const operationId = n => n.toString(16).padStart(32,'0');
const receipt = (uid,id) => db.doc(`userOperations/${uid}/requests/${id}`);

test('create operation replay cannot create another convoy even after ending',async()=>{
  const operationId='a'.repeat(32);
  const first=await createSession(db,'owner',{name:'Once',operationId});
  await endSession(db,'owner',{convoyId:first.convoyId});
  const replay=await createSession(db,'owner',{name:'Ignored replacement',operationId});
  assert.equal(replay.convoyId,first.convoyId);
  assert.equal((await db.collection('convoys').get()).size,1);
  assert.equal((await resolveOperation(db,'owner',{operationId,kind:'createConvoy'})).status,'committed');
});

test('recovery fences a create that was not committed and delayed delivery cannot create',async()=>{
  const id=operationId(1);
  assert.equal((await resolveOperation(db,'owner',{operationId:id,kind:'createConvoy'})).status,'cancelled');
  await assert.rejects(createSession(db,'owner',{name:'Late',operationId:id}),{code:'failed-precondition'});
  assert.equal((await db.collection('convoys').get()).size,0);
  assert.equal((await db.collection('userSessions').get()).size,0);
});

test('cancel racing create has one atomic winner and no partial result',async()=>{
  const id=operationId(2);
  await Promise.allSettled([createSession(db,'owner',{name:'Race',operationId:id}),
    resolveOperation(db,'owner',{operationId:id,kind:'createConvoy'})]);
  const state=(await receipt('owner',id).get()).get('status');
  const count=(await db.collection('convoys').get()).size;
  assert.equal(count,state==='committed'?1:0);
  assert.equal((await db.collection('userSessions').get()).size,count);
});

test('invitation receipt contains no secret or hash and replay never rotates again',async()=>{
  const f=await fixture(); const id=operationId(3);
  const invite=await issueInvitation(db,'owner',{convoyId:f.convoyId,operationId:id});
  const before=(await db.doc(`convoys/${f.convoyId}`).get()).get('inviteVersion');
  const replay=await issueInvitation(db,'owner',{convoyId:f.convoyId,operationId:id});
  assert.equal(replay.invitationUnavailable,true); assert.equal(replay.secret,undefined);
  const record=(await receipt('owner',id).get()).data();
  assert.deepEqual(Object.keys(record.result).sort(),['convoyId','invitationUnavailable','version']);
  assert.equal(JSON.stringify(record).includes(invite.secret),false);
  assert.equal((await db.doc(`convoys/${f.convoyId}`).get()).get('inviteVersion'),before);
});

test('join receipt prevents a late duplicate from rejoining after leave',async()=>{
  const f=await fixture(); const id=operationId(4);
  await redeemInvitation(db,'member',{secret:f.secret,operationId:id});
  await leaveSession(db,'member',{convoyId:f.convoyId});
  await redeemInvitation(db,'member',{secret:f.secret,operationId:id});
  assert.equal((await db.doc('userSessions/member').get()).exists,false);
  assert.equal(Object.hasOwn((await db.doc(`convoys/${f.convoyId}`).get()).get('members'),'member'),false);
});

test('leave and end receipts replay without altering a newer pointer',async()=>{
  const f=await fixture(); await redeemInvitation(db,'member',{secret:f.secret});
  const left={convoyId:f.convoyId,operationId:operationId(5)};
  await leaveSession(db,'member',left);
  const next=await createSession(db,'member',{name:'Next'});
  await leaveSession(db,'member',left);
  assert.equal((await db.doc('userSessions/member').get()).get('convoyId'),next.convoyId);
  const ended={convoyId:f.convoyId,operationId:operationId(6)};
  await endSession(db,'owner',ended);
  const ownerNext=await createSession(db,'owner',{name:'Owner next'});
  await endSession(db,'owner',ended);
  assert.equal((await db.doc('userSessions/owner').get()).get('convoyId'),ownerNext.convoyId);
});

test('cancellation fences invitation, join, leave and end mutations',async()=>{
  const f=await fixture(); await redeemInvitation(db,'member',{secret:f.secret});
  const cases=[['createConvoyInvitation','owner',issueInvitation,{convoyId:f.convoyId}],
    ['joinConvoy','other',redeemInvitation,{secret:f.secret}],
    ['leaveConvoy','member',leaveSession,{convoyId:f.convoyId}],
    ['endConvoy','owner',endSession,{convoyId:f.convoyId}]];
  const before=(await db.doc(`convoys/${f.convoyId}`).get()).data();
  for(let n=0;n<cases.length;n++){
    const [kind,uid,mutate,data]=cases[n]; const id=operationId(10+n);
    await resolveOperation(db,uid,{operationId:id,kind});
    await assert.rejects(mutate(db,uid,{...data,operationId:id}),{code:'failed-precondition'});
  }
  assert.deepEqual((await db.doc(`convoys/${f.convoyId}`).get()).data(),before);
  assert.equal((await db.doc('userSessions/other').get()).exists,false);
});

test('operation receipts are owner get-only; all direct writes, lists and cross-user reads denied',async()=>{
  const id=operationId(20); await resolveOperation(db,'owner',{operationId:id,kind:'createConvoy'});
  const owner=env.authenticatedContext('owner').firestore();
  const other=env.authenticatedContext('other').firestore();
  const path=`userOperations/owner/requests/${id}`;
  await assertSucceeds(getDoc(doc(owner,path)));
  await assertFails(getDoc(doc(other,path)));
  await assertFails(getDoc(doc(env.unauthenticatedContext().firestore(),path)));
  await assertFails(getDocs(collection(owner,'userOperations/owner/requests')));
  await assertFails(setDoc(doc(owner,path),{status:'committed'}));
  await assertFails(deleteDoc(doc(owner,path)));
});

test('receipt creation failure rolls back convoy and pointer writes',async()=>{
  const original=db.runTransaction;
  db.runTransaction=fn=>original.call(db,tx=>{
    const create=tx.create.bind(tx);
    tx.create=(ref,data)=>{if(ref.path.startsWith('userOperations/'))throw new Error('injected receipt failure');return create(ref,data);};
    return fn(tx);
  });
  try { await assert.rejects(createSession(db,'owner',{name:'Rollback',operationId:operationId(21)})); }
  finally {db.runTransaction=original;}
  assert.equal((await db.collection('convoys').get()).size,0);
  assert.equal((await db.collection('userSessions').get()).size,0);
  assert.equal((await receipt('owner',operationId(21)).get()).exists,false);
});

test('recovery callable authenticates and validates operation ID and kind',async()=>{
  const id=operationId(22);
  assert.equal((await call({operationId:id,kind:'createConvoy'},undefined,'resolveConvoyOperation')).status,401);
  const user=await token();
  for(const data of [{operationId:'bad',kind:'createConvoy'},{operationId:id,kind:'other'},
    {operationId:id,kind:'createConvoy',uid:'forged'}]){
    assert.equal((await call(data,user.idToken,'resolveConvoyOperation')).body.error.status,'INVALID_ARGUMENT');
  }
  assert.equal((await call({operationId:id,kind:'createConvoy'},user.idToken,'resolveConvoyOperation')).body.result.status,'cancelled');
  assert.equal((await call({name:'Late',operationId:id},user.idToken)).body.error.status,'FAILED_PRECONDITION');
  assert.equal((await db.collection('convoys').get()).size,0);
});

test('new cancellation records are throttled but existing recovery is idempotent',async()=>{
  for(let i=0;i<20;i++)await resolveOperation(db,'owner',{operationId:operationId(100+i),kind:'createConvoy'});
  await assert.rejects(resolveOperation(db,'owner',{operationId:operationId(120),kind:'createConvoy'}),{code:'resource-exhausted'});
  assert.equal((await resolveOperation(db,'owner',{operationId:operationId(100),kind:'createConvoy'})).status,'cancelled');
});
