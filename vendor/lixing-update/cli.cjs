'use strict';

const fs = require('node:fs');
const path = require('node:path');
const readline = require('node:readline');
const {spawn} = require('node:child_process');
const {randomUUID} = require('node:crypto');
const {appConfigDir, plain} = require('./config.cjs');
const {createReleaseChannel, compare} = require('./lixing-update.cjs');

function writeJsonLine(value) {
  if (!process.stdout.destroyed) process.stdout.write(JSON.stringify(value) + '\n');
}

const [, , option, configFile] = process.argv;
if (option !== '--config' || !configFile || process.argv.length !== 4) {
  throw new Error('Usage: cli.cjs --config <local release-config.json>');
}
const config = JSON.parse(fs.readFileSync(configFile, 'utf8'));
const {
  appId, repo, currentVersion, platform, arch, channel, setupFilename, setupArguments = [], hotfixKinds = [], tagPrefix = 'v'
} = config;
if (setupFilename !== undefined && (typeof setupFilename !== 'string'
    || !/^[A-Za-z0-9._-]*\{version\}[A-Za-z0-9._-]*\.exe$/.test(setupFilename))) {
  throw new Error('Invalid setupFilename pattern');
}
if (!Array.isArray(setupArguments) || setupArguments.length > 12
    || setupArguments.some(arg => typeof arg !== 'string' || arg.length > 260 || arg.includes('\0'))) {
  throw new Error('Invalid local setupArguments');
}
if (!Array.isArray(hotfixKinds) || hotfixKinds.length > 12
    || hotfixKinds.some(kind => typeof kind !== 'string' || !/^[a-z][a-z0-9-]{0,31}$/.test(kind))) {
  throw new Error('Invalid local hotfixKinds');
}
const certificate = fs.readFileSync(path.join(__dirname, 'release-certificate.cer'));
const channelClient = createReleaseChannel({
  appId, repo, currentVersion, platform, arch, channel, certificate, hotfixKinds, tagPrefix,
  setupName: setupFilename ? version => setupFilename.replace('{version}', version) : undefined
});
const receipt = plain(path.join(appConfigDir(appId), 'update-receipt.json'));
let staged = null;
let stagedKind = null;
let connected = true;
let operation = false;
let state = {phase: 'idle', currentVersion, available: false, canSetup: false, canHotfix: false};

function readReceipt() {
  try {
    return JSON.parse(fs.readFileSync(receipt, 'utf8'));
  } catch (error) {
    if (error.code !== 'ENOENT') throw error;
    return null;
  }
}

function saveReceipt(installation) {
  const temporary = receipt + '.' + randomUUID() + '.tmp';
  fs.mkdirSync(path.dirname(receipt), {recursive: true});
  try {
    fs.writeFileSync(temporary, JSON.stringify(installation) + '\n', {flag: 'wx', mode: 0o600, flush: true});
    fs.renameSync(temporary, receipt);
  } finally {
    fs.rmSync(temporary, {force: true});
  }
}

function publish(update) {
  state = {...state, ...update};
  if (connected) writeJsonLine({event: 'state', state});
}

function getInstallation() {
  const installation = readReceipt();
  if (installation && compare(currentVersion, installation.version) >= 0 && installation.phase !== 'error') {
    return {...installation, phase: 'completed'};
  }
  return installation;
}

function runInstaller(file) {
  return new Promise((resolve, reject) => {
    const child = spawn(file, setupArguments, {windowsHide: true, shell: false, stdio: 'ignore'});
    child.once('error', reject);
    child.once('exit', (code, signal) => {
      if (code === 0) resolve();
      else reject(new Error('安装未完成（' + (signal ?? '退出代码 ' + code) + '）'));
    });
  });
}

async function check() {
  staged = null;
  publish({phase: 'checking', error: null, staged: null, canSetup: false, canHotfix: false});
  const release = await channelClient.discover();
  publish({...release, phase: release.available ? 'available' : 'current', checkedAt: new Date().toISOString()});
}

async function download(command) {
  const kind = command.kind ?? 'setup';
  if (!['setup', 'hotfix'].includes(kind)) throw new Error('未知更新资产类型');
  staged = null;
  stagedKind = null;
  publish({phase: 'downloading', error: null, staged: null, receivedBytes: 0, totalBytes: null});
  staged = await channelClient.download(kind, (receivedBytes, totalBytes) => publish({receivedBytes, totalBytes}));
  stagedKind = kind;
  publish({phase: 'ready', staged: {kind, version: staged.version}});
}

function verifyHotfix() {
  if (stagedKind !== 'hotfix' || !staged) throw new Error('请先下载热更新');
  const bytes = channelClient.verifyHotfix(staged);
  return {hotfix: {version: staged.version, spec: staged.spec, bytesBase64: bytes.toString('base64')}};
}

async function install() {
  if (stagedKind !== 'setup' || !staged) throw new Error('请先下载完整更新');
  const file = channelClient.verifySetup(staged);
  const intended = {version: staged.version, phase: 'installing'};
  saveReceipt(intended);
  publish({phase: 'installing', installation: intended, error: null});
  try {
    await runInstaller(file);
  } catch (error) {
    const installation = {...intended, phase: 'error', message: error.message};
    saveReceipt(installation);
    publish({installation});
    throw error;
  }
  const installation = {...intended, phase: 'awaiting-restart'};
  saveReceipt(installation);
  publish({phase: 'awaiting-restart', installation, staged: null});
  staged = null;
}

const actions = {check, download, install, verifyHotfix};
async function receive(line) {
  let id = null;
  try {
    if (line.length > 4096) throw new Error('Update request exceeds size limit');
    const command = JSON.parse(line);
    id = command.id;
    const action = command.action;
    if (!Number.isSafeInteger(id) && (typeof id !== 'string' || id.length > 64)) throw new Error('Invalid request id');
    if (action === 'snapshot') return writeJsonLine({id, result: {ok: true, state}});
    if (!Object.hasOwn(actions, action)) throw new Error('Unknown update action');
    if (operation) throw new Error('另一项更新操作正在进行');
    operation = true;
    try {
      const result = await actions[action](command);
      if (connected) writeJsonLine({id, result: {ok: true, state, ...result}});
    } catch (error) {
      publish({phase: 'error', error: error.message});
      throw error;
    } finally {
      operation = false;
    }
  } catch (error) {
    if (connected) writeJsonLine({id, result: {ok: false, error: error.message, state}});
  }
}

process.stdout.on('error', error => {
  if (error.code !== 'EPIPE') throw error;
  connected = false;
});
const input = readline.createInterface({input: process.stdin, crlfDelay: Infinity, terminal: false});
input.on('line', line => void receive(line));
input.on('close', () => { connected = false; });
publish({installation: getInstallation()});
