// ===============================================================
//  趵突泉之光 · iOS (Scriptable)
//  取水（慧生活798） / 洗澡（胖乖生活） / 洗衣（智慧笑联小程序）
//
//  用法：装 Scriptable → 新建脚本 → 粘贴本文件 → 运行
//       可长按脚本「添加到主屏幕」，像 App 一样使用
//
//  版本 7.10.1 ｜ 2026-10-16
//   · 补上洗澡订单的「结算付款」。胖乖生活是先开门、洗完才结算，
//     旧版只发 remoteEnd 就收尾，账没结 —— 每次洗澡留一笔未支付订单，
//     攒到服务端用 code 2128 把下一次下单整个堵死（报错还伪装成「预览失败」）。
//   · 被 code 2128 拦住时，直接给出「去付款」入口。
//   · 新增「支付这一单」：结束完自动跳转支付宝，可回查是否付成功。
// ===============================================================

// ---------------------------------------------------------------
// 1. SHA-256（纯 JS，无依赖）—— 已与 Python/Java 版对照验证一致
// ---------------------------------------------------------------
function sha256hex(ascii) {
  function rightRotate(value, amount) {
    return (value >>> amount) | (value << (32 - amount));
  }
  var mathPow = Math.pow;
  var maxWord = mathPow(2, 32);
  var lengthProperty = 'length';
  var i, j;
  var result = '';

  var words = [];
  var asciiBitLength = ascii[lengthProperty] * 8;

  var hash = sha256hex.h = sha256hex.h || [];
  var k = sha256hex.k = sha256hex.k || [];
  var primeCounter = k[lengthProperty];

  var isComposite = {};
  for (var candidate = 2; primeCounter < 64; candidate++) {
    if (!isComposite[candidate]) {
      for (i = 0; i < 313; i += candidate) {
        isComposite[i] = candidate;
      }
      hash[primeCounter] = (mathPow(candidate, .5) * maxWord) | 0;
      k[primeCounter++] = (mathPow(candidate, 1 / 3) * maxWord) | 0;
    }
  }

  ascii += '\x80';
  while (ascii[lengthProperty] % 64 - 56) ascii += '\x00';
  for (i = 0; i < ascii[lengthProperty]; i++) {
    j = ascii.charCodeAt(i);
    if (j >> 8) return;
    words[i >> 2] |= j << ((3 - i) % 4) * 8;
  }
  words[words[lengthProperty]] = ((asciiBitLength / maxWord) | 0);
  words[words[lengthProperty]] = (asciiBitLength);

  for (j = 0; j < words[lengthProperty];) {
    var w = words.slice(j, j += 16);
    var oldHash = hash;
    hash = hash.slice(0, 8);

    for (i = 0; i < 64; i++) {
      var w15 = w[i - 15], w2 = w[i - 2];
      var a = hash[0], e = hash[4];
      var temp1 = hash[7]
        + (rightRotate(e, 6) ^ rightRotate(e, 11) ^ rightRotate(e, 25))
        + ((e & hash[5]) ^ ((~e) & hash[6]))
        + k[i]
        + (w[i] = (i < 16) ? w[i] : (
            w[i - 16]
            + (rightRotate(w15, 7) ^ rightRotate(w15, 18) ^ (w15 >>> 3))
            + w[i - 7]
            + (rightRotate(w2, 17) ^ rightRotate(w2, 19) ^ (w2 >>> 10))
          ) | 0
        );
      var temp2 = (rightRotate(a, 2) ^ rightRotate(a, 13) ^ rightRotate(a, 22))
        + ((a & hash[1]) ^ (a & hash[2]) ^ (hash[1] & hash[2]));

      hash = [(temp1 + temp2) | 0].concat(hash);
      hash[4] = (hash[4] + temp1) | 0;
    }

    for (i = 0; i < 8; i++) {
      hash[i] = (hash[i] + oldHash[i]) | 0;
    }
  }

  for (i = 0; i < 8; i++) {
    for (j = 3; j + 1; j--) {
      var b = (hash[i] >> (j * 8)) & 255;
      result += ((b < 16) ? 0 : '') + b.toString(16);
    }
  }
  return result;
}

// ---------------------------------------------------------------
// 2. 常量
// ---------------------------------------------------------------
const WATER = {
  base: 'https://i.ilife798.com',
  versionCode: '31007',
};
const PG = {
  base: 'https://userapi.qiekj.com',
  secret: 'nFU9pbG8YQoAe1kFh+E7eyrdlSLglwEJeA0wwHB1j5o=',
  appVersion: '1.143.0',
  appVersionCode: '290',
  pkg: 'com.qiekj.user',
  channel: 'android_app',
};
const XIAOLIAN_APPID = '2021001189641702';
const LAUNDRY_PAGE =
  '&page=pages%2Fdevice%2Fwashing%2Findex%3F__id__%3D6' +
  '%26query%3D%25257B%252522deviceType%252522%25253A4%25257D' +
  '%26__appxPageId%3D14';

// 洗澡的位置 —— 风控签名要带经纬度。趵突泉校区。
const PG_LAT = '36.651077673510976';
const PG_LNG = '117.05471881019238';

// 支付宝「跳转支付」的官方入口，不需要集成 SDK 就能唤起收银台。
const ALIPAY_ORDER_SCHEME =
  'alipays://platformapi/startapp?appId=20000125&orderSuffix=';

// ---------------------------------------------------------------
// 3. 本地存储
// ---------------------------------------------------------------
const FM = FileManager.local();
const DIR = FM.joinPath(FM.documentsDirectory(), 'baotu');
const DB_PATH = FM.joinPath(DIR, 'store.json');

function loadDB() {
  try {
    if (FM.fileExists(DB_PATH)) {
      const d = JSON.parse(FM.readString(DB_PATH));
      d.devices = d.devices || [];
      d.current = d.current || {};
      d.tokens = d.tokens || {};
      d.phones = d.phones || {};
      d.pending = d.pending || null;
      return d;
    }
  } catch (e) { }
  return { devices: [], current: {}, tokens: {}, phones: {}, pending: null };
}

function saveDB(db) {
  if (!FM.fileExists(DIR)) { FM.createDirectory(DIR, true); }
  FM.writeString(DB_PATH, JSON.stringify(db));
}

function devicesOf(db, type) {
  return db.devices
    .filter(d => d.type === type)
    .sort((a, b) => (b.usedAt || 0) - (a.usedAt || 0));
}

function rememberDevice(db, type, id, group, sub, extra) {
  if (!id) { return; }
  let d = db.devices.find(x => x.type === type && x.id === id);
  if (!d) {
    d = { type, id, name: '', group: '', sub: '', extra: '' };
    db.devices.push(d);
  }
  if (group) { d.group = group; }
  if (sub) { d.sub = sub; }
  if (extra) { d.extra = extra; }
  d.usedAt = Date.now();
}

function currentDevice(db, type) {
  const id = db.current[type];
  if (!id) { return null; }
  return db.devices.find(d => d.type === type && d.id === id) || null;
}

// ---------------------------------------------------------------
// 4. HTTP
// ---------------------------------------------------------------
async function httpSend(method, url, headers, body) {
  const req = new Request(url);
  req.method = method;
  req.headers = headers;
  if (body != null) { req.body = body; }
  const text = await req.loadString();
  let json = null;
  try { json = JSON.parse(text); } catch (e) { }
  return { status: req.response.statusCode, text, json };
}

async function httpGet(url, headers) {
  return httpSend('GET', url, headers, null);
}

/** 表单提交（胖乖生活用） */
async function httpPost(url, headers, form) {
  const h = Object.assign({}, headers);
  h['Content-Type'] = 'application/x-www-form-urlencoded;charset=UTF-8';
  const body = Object.keys(form || {})
    .map(k => encodeURIComponent(k) + '=' + encodeURIComponent(form[k] == null ? '' : form[k]))
    .join('&');
  return httpSend('POST', url, h, body);
}

/** JSON 提交（慧生活798 的登录接口用） */
async function httpPostJson(url, headers, obj) {
  const h = Object.assign({}, headers);
  h['Content-Type'] = 'application/json';
  return httpSend('POST', url, h, JSON.stringify(obj || {}));
}

function pgReqSign(path, token, ts) {
  return sha256hex(
    'appSecret=' + PG.secret +
    '&channel=' + PG.channel +
    '&timestamp=' + ts +
    '&token=' + (token || '') +
    '&version=' + PG.appVersion +
    '&' + path
  );
}

function pgRiskSign(path, token, ts, imei, cc, lat, lng) {
  return sha256hex(
    'appSecret=' + PG.secret +
    '&categoryCode=' + cc +
    '&channel=' + PG.channel +
    '&imei=' + imei +
    '&lat=' + lat +
    '&lng=' + lng +
    '&timestamp=' + ts +
    '&token=' + (token || '') +
    '&version=' + PG.appVersion +
    '&' + path
  );
}

async function pgPost(path, form, token, risk) {
  const ts = String(Date.now());
  const headers = {
    'Authorization': token || '',
    'Version': PG.appVersion,
    'channel': PG.channel,
    'phoneBrand': Device.name() || 'iPhone',
    'deviceId': 'ios',
    'timestamp': ts,
    'sign': pgReqSign(path, token, ts),
    'User-Agent': 'QEUser/' + PG.appVersion + ' (' + PG.pkg + '; build:' +
      PG.appVersionCode + '; iOS ' + Device.systemVersion() + '; userChannel:' +
      PG.channel + '; version:' + PG.appVersion + ')',
  };
  if (risk) {
    headers['imei'] = risk.imei;
    headers['categoryCode'] = risk.categoryCode;
    headers['lat'] = risk.lat;
    headers['lng'] = risk.lng;
    headers['orderRiskSign'] = pgRiskSign(path, token, ts, risk.imei,
      risk.categoryCode, risk.lat, risk.lng);
    headers['orderRiskTimestamp'] = ts;
  }
  const body = Object.assign({}, form || {});
  if (token) { body.token = token; }
  return httpPost(PG.base + path, headers, body);
}

function waterHeaders(token) {
  const h = {
    'ApplicationType': '1,1',
    'VersionCode': WATER.versionCode,
    'user-agent': 'Android_ilife798_' + WATER.versionCode,
  };
  if (token) { h['Authorization'] = token; }
  return h;
}

/** 取水的查询类接口（GET，token 走请求头） */
async function waterGet(path, token) {
  return httpGet(WATER.base + path, waterHeaders(token));
}

/** 取水的提交类接口（POST + JSON body） */
async function waterPostJson(path, obj, token) {
  return httpPostJson(WATER.base + path, waterHeaders(token), obj);
}

// ---------------------------------------------------------------
// 5. 界面小工具
// ---------------------------------------------------------------
async function ask(title, message, fields, okLabel) {
  const a = new Alert();
  a.title = title;
  if (message) { a.message = message; }
  (fields || []).forEach(f => a.addTextField(f.hint, f.value || ''));
  a.addAction(okLabel || '确定');
  a.addCancelAction('取消');
  const idx = await a.present();
  if (idx === -1) { return null; }
  return (fields || []).map((f, i) => a.textFieldValue(i));
}

async function info(title, message) {
  const a = new Alert();
  a.title = title;
  a.message = message;
  a.addAction('好');
  await a.present();
}

async function menu(title, message, items) {
  const t = new UITable();
  t.showSeparators = true;
  if (title) {
    const r = new UITableRow();
    r.isHeader = true;
    r.addCell(UITableCell.text(title));
    t.addRow(r);
  }
  if (message) {
    const r = new UITableRow();
    r.addCell(UITableCell.text(message));
    t.addRow(r);
  }
  items.forEach(it => {
    const r = new UITableRow();
    r.addCell(UITableCell.text(it.label));
    if (it.sub) { r.addCell(UITableCell.text(it.sub)); }
    r.dismissOnSelect = true;
    r.onSelect = () => { t._picked = it.value; };
    t.addRow(r);
  });
  await t.present();
  return t._picked;
}

function copyOrPaste(txt) {
  if (txt) { Pasteboard.copy(txt); }
  return Pasteboard.paste();
}

// ---------------------------------------------------------------
// 6. 取水（慧生活798）
// ---------------------------------------------------------------
async function waterLogin(db) {
  const phone0 = db.phones.water || '';
  const r1 = await ask('登录取水账号', '第 1 步：手机号', [{ hint: '手机号', value: phone0 }], '下一步');
  if (!r1) { return false; }
  const phone = r1[0].trim();
  if (!phone) { return false; }

  const seed = Math.random();
  const capUrl = WATER.base + '/api/v1/captcha/?s=' + seed + '&r=' + Date.now();
  let capImg;
  try {
    const req = new Request(capUrl);
    capImg = await req.loadImage();
    QuickLook.present(capImg);
  } catch (e) {
    await info('验证码获取失败', String(e));
    return false;
  }

  const r2 = await ask('第 2 步', '看图输入那 5 个字符', [{ hint: '图形验证码' }], '获取短信');
  if (!r2) { return false; }
  const cap = r2[0].trim();

  const s1 = await waterPostJson('/api/v1/acc/login/code',
    { un: phone, authCode: cap, s: seed }, null);
  if (!s1.json || s1.json.code !== 0) {
    await info('发送短信失败', (s1.json && s1.json.msg) || s1.text);
    return false;
  }

  const r3 = await ask('第 3 步', '短信已发送，输入验证码', [{ hint: '短信验证码' }], '登录');
  if (!r3) { return false; }
  const s2 = await waterPostJson('/api/v1/acc/login',
    { un: phone, authCode: r3[0].trim() }, null);

  const d = s2.json && s2.json.data;
  const al = d && d.al;
  if (!al || !al.token) {
    await info('登录失败', (s2.json && s2.json.msg) || s2.text);
    return false;
  }
  db.tokens.water = al.token;
  db.phones.water = phone;
  saveDB(db);
  return true;
}

async function waterStatus(db, deviceId) {
  const token = db.tokens.water;
  if (!token) { return { label: '未登录' }; }
  const r = await waterGet('/api/v1/ui/app/dev/status?did=' +
    encodeURIComponent(deviceId) + '&more=true', token);
  const j = r.json;
  if (!j || j.code !== 0) { return { label: '离线' }; }
  const gene = j.data && j.data.device && j.data.device.gene;
  if (!gene) { return { label: '—' }; }
  const map = {
    0: '关闭', 1: '已激活', 2: '异常', 10: '使用中',
    20: '冲洗中', 30: '准备中', 98: '已停用', 99: '空闲',
  };
  return { label: map[gene.status] || ('状态' + gene.status), raw: gene };
}

async function waterLoadDevice(db, deviceId) {
  const token = db.tokens.water;
  const r = await waterGet('/api/v1/ui/app/dev/home/1?did=' +
    encodeURIComponent(deviceId) + '&apply=6', token);
  const j = r.json;
  const dev = j && j.data && j.data.device;
  if (!dev) { return null; }
  const bm = dev.bm || {};
  const ep = dev.ep || {};
  rememberDevice(db, 'water', dev.id,
    ep.name || '',
    (ep.name ? ep.name + ' · ' : '') + (bm.brand || '') + ' ' + (bm.model || ''),
    '');
  saveDB(db);
  return { dev, bm, ep };
}

async function waterMenu(db) {
  while (true) {
    if (!db.tokens.water) {
      const ok = await waterLogin(db);
      if (!ok) { return 'main'; }
    }
    const cur = currentDevice(db, 'water') || { id: '' };
    const id = cur.id;
    let st = { label: '…' };
    try { st = await waterStatus(db, id); } catch (e) { }

    let dev = null;
    try { dev = await waterLoadDevice(db, id); } catch (e) { }
    const curName = cur.name || (dev && dev.ep && dev.ep.name) || id;

    const pick = await menu('取水',
      (curName || id) + '\n设备号 ' + id + '\n状态：' + st.label,
      [
        { label: '▶  激活取水', sub: '等价于扫机器上的码', value: 'start' },
        { label: '⏹  结束', value: 'end' },
        { label: '🔄  刷新状态', value: 'refresh' },
        { label: '📱  切换设备', sub: devicesOf(db, 'water').length + ' 台已记住', value: 'devices' },
        { label: '🚪  退出登录', value: 'logout' },
        { label: '←  返回', value: 'back' },
      ]);

    if (!pick || pick === 'back') { return 'main'; }

    if (pick === 'start') {
      const a = new Alert();
      a.title = '确认激活？';
      a.message = '将在 ' + id + ' 上激活取水。\n激活后请按机器上的启动键。';
      a.addAction('激活');
      a.addCancelAction('取消');
      if (await a.present() === 0) {
        const r = await waterGet('/api/v1/dev/start?did=' +
          encodeURIComponent(id) + '&ptype=21', db.tokens.water);
        await info('激活结果', (r.json && r.json.code === 0)
          ? '✅ 已激活，请在机器上按键取水。'
          : ('失败：' + ((r.json && r.json.msg) || r.text)));
      }
    } else if (pick === 'end') {
      const r = await waterGet('/api/v1/dev/end?did=' +
        encodeURIComponent(id) + '&rcp=true', db.tokens.water);
      await info('结束结果', (r.json && r.json.code === 0) ? '✅ 已结束。' : ('失败：' + r.text));
    } else if (pick === 'refresh') {
      continue;
    } else if (pick === 'logout') {
      delete db.tokens.water;
      saveDB(db);
      return 'main';
    } else if (pick === 'devices') {
      const next = await devicesMenu(db, 'water');
      if (next === 'exit') { return 'exit'; }
    }
  }
}

// ---------------------------------------------------------------
// 7. 洗澡（胖乖生活）
// ---------------------------------------------------------------
async function pgLogin(db) {
  const phone0 = db.phones.pg || '';
  const r1 = await ask('登录洗澡账号', '手机号', [{ hint: '手机号', value: phone0 }], '获取短信');
  if (!r1) { return false; }
  const phone = r1[0].trim();
  if (!phone) { return false; }

  const s1 = await pgPost('/common/sms/sendCode', { phone: phone, template: '1' }, null, null);
  if (!s1.json || s1.json.code !== 0) {
    await info('发送短信失败', (s1.json && s1.json.msg) || s1.text);
    return false;
  }
  const r2 = await ask('输入验证码', '短信已发送', [{ hint: '短信验证码' }], '登录');
  if (!r2) { return false; }

  const s2 = await pgPost('/user/reg',
    { channel: PG.channel, phone: phone, verify: r2[0].trim() }, null, null);
  const token = s2.json && s2.json.data && s2.json.data.token;
  if (!token) {
    await info('登录失败', (s2.json && s2.json.msg) || s2.text);
    return false;
  }
  db.tokens.pg = token;
  db.phones.pg = phone;
  saveDB(db);
  return true;
}

async function pgResolve(db, sn) {
  const token = db.tokens.pg;
  const scan = await pgPost('/goods/scan/v2', { SN: sn }, token, null);
  const sd = scan.json && scan.json.data;
  if (!sd) { throw new Error('扫码失败：' + ((scan.json && scan.json.msg) || scan.text)); }

  const det = await pgPost('/goods/normal/details', { goodsId: sd.id }, token, null);
  const dd = det.json && det.json.data;
  if (!dd) { throw new Error('查询设备失败：' + ((det.json && det.json.msg) || det.text)); }

  const sku = await pgPost('/goods/normal/skus', { goodsId: sd.id }, token, null);
  if (pgIsUnpaidBlock(sku)) {
    // 被拦时 data 是个**对象**（带 incompleteOrderId）而不是数组，取不到 skuId。
    // 继续走下去只会从 /trade/preview 拿到 code -1 + msg null，界面上就是一句
    // 没头没脑的「预览失败」。所以在这里就把订单号抛出去。
    const e = new Error('有未支付订单需要完成');
    e.isUnpaid = true;
    e.unpaidOrderId = pgIncompleteOrderId(sku);
    e.partial = { sn: dd.imei, categoryCode: sd.categoryCode, goodsId: sd.id };
    throw e;
  }
  const skus = sku.json && sku.json.data;
  const s0 = (skus && skus.length) ? skus[0] : {};

  rememberDevice(db, 'shower', dd.imei, dd.orgName || '',
    (dd.orgName ? dd.orgName + ' · ' : '') + (dd.name || ''), sd.id);
  saveDB(db);

  return {
    sn: dd.imei,
    goodsId: sd.id,
    shopId: dd.shopId,
    orgId: dd.orgId,
    categoryCode: sd.categoryCode,
    orgName: dd.orgName,
    goodsName: dd.name,
    isBluetooth: dd.isBluetooth,
    skuId: s0.skuId,
    skuName: s0.name,
    skuPrice: s0.price,
  };
}

function pgItems(d) {  return JSON.stringify([{
    goodsId: d.goodsId,
    skuId: String(d.skuId),
    amount: '1',
    soldType: '1',
    num: '1',
    price: String(d.skuPrice || '0'),
  }]);
}

async function showerMenu(db) {
  while (true) {
    if (!db.tokens.pg) {
      const ok = await pgLogin(db);
      if (!ok) { return 'main'; }
    }
    const cur = currentDevice(db, 'shower');
    const sn = cur ? cur.id : '';

    // 菜单项按状态动态给：有在途订单时最前面提示，并给结束/撤销两个出口
    const items = [
      { label: '🚿  查询 / 开始洗澡', sub: '会先解析设备并算价', value: 'start' },
      { label: '📷  扫描机器二维码', sub: '用相机扫码自动填入 SN', value: 'scan' },
      { label: '⌨️  手动输入 SN', value: 'manual' },
      { label: '💳  支付方式', sub: pgPayTypeName(db.payTypes, db.payType || PAY_ALIPAY), value: 'paytype' },
      { label: '📱  切换设备', sub: devicesOf(db, 'shower').length + ' 台已记住', value: 'devices' },
    ];
    if (db.pending) {
      items.unshift({ label: '✅  结束这一单', sub: '结束完会自动跳去付款', value: 'endpending' });
      if (db.pending.orderNo) {
        items.splice(1, 0, { label: '💰  支付这一单', sub: '不付掉会挡住下一次洗澡', value: 'paypending' });
      } else {
        items.splice(1, 0, { label: '↩️  撤销这一单', sub: '还没拿到订单号，只能撤销', value: 'cancelpending' });
      }
      items.unshift({ label: '⚠️  有一笔未结束的订单', sub: (db.pending.orderNo || db.pending.orderId), value: 'noop' });
    }
    items.push({ label: '🚪  退出登录', value: 'logout' });
    items.push({ label: '←  返回', value: 'back' });

    const pick = await menu('洗澡',
      (cur ? (cur.name || cur.sub || cur.id) : '尚未查询设备') +
      '\nSN ' + sn,
      items);

    if (!pick || pick === 'back') { return 'main'; }

    if (pick === 'noop') {
      // 只是提示行，什么都不做
    } else if (pick === 'endpending') {
      await showerEndPending(db);
    } else if (pick === 'paypending') {
      await pgSettle(db, db.pending.orderNo,
        db.pending.payType || db.payType || PAY_ALIPAY, pgRiskOf(db.pending));
    } else if (pick === 'cancelpending') {
      await showerCancelPending(db);
    } else if (pick === 'paytype') {
      const list = (db.payTypes && db.payTypes.length) ? db.payTypes : [];
      if (!list.length) {
        await info('还不知道有哪些支付方式', '先点一次「查询 / 开始洗澡」，\n解析设备时会顺带把可用渠道取回来。');
      } else {
        const chosen = await menu('支付方式',
          '服务端为这台机器返回的渠道。\n没登录/没解析过设备时可能取不到。',
          list.map(x => ({
            label: (x.isDefault ? '★ ' : '   ') + x.name,
            sub: 'payType = ' + x.type + (x.isDefault ? '（官方默认）' : ''),
            value: x.type,
          })).concat([{ label: '←  返回', value: '' }]));
        if (chosen) { db.payType = chosen; saveDB(db); }
      }
    } else if (pick === 'scan') {
      await info('扫码提示', '请用相机扫机器屏幕上的二维码，\n再回到这里选择「手动输入 SN」粘贴。\n\n或直接用「手动输入 SN」输入。');
    } else if (pick === 'manual') {
      const r = await ask('输入机器 SN', '可粘贴二维码链接，会自动提取 SN',
        [{ hint: 'SN 或二维码链接' }], '确定');
      if (r) {
        const sn2 = extractSn(r[0]);
        if (sn2) {
          db.current.shower = sn2;
          saveDB(db);
        }
      }
    } else if (pick === 'logout') {
      delete db.tokens.pg;
      saveDB(db);
      return 'main';
    } else if (pick === 'devices') {
      const next = await devicesMenu(db, 'shower');
      if (next === 'exit') { return 'exit'; }
    } else if (pick === 'start') {
      await showerStart(db, sn);
    }
  }
}

function extractSn(raw) {
  let s = (raw || '').trim();
  const i = s.toUpperCase().indexOf('SN=');
  if (i >= 0) {
    s = s.substring(i + 3);
    const amp = s.indexOf('&');
    if (amp >= 0) { s = s.substring(0, amp); }
  }
  return s.replace(/[^0-9A-Za-z]/g, '');
}

// ---------------------------------------------------------------
// 7.5 支付方式 / 订单收尾
// ---------------------------------------------------------------
// payType 取值来自反编译出的 PayTypeEnum（不是猜的）：
//   2 钱包(NOT 默认) / 13 支付宝 / 15 支付宝免密 / 32 微信 / 53 云闪付无感
// 早期这里写死 '2'（钱包）—— 实测那台机器的可用支付方式里**根本没有钱包**，
// 是个无效值。现在改成从官方接口取，默认用服务端标 defaultFlag 的那个。
const PAY_ALIPAY = '13';

async function pgAvailablePayTypes(d, token) {
  const r = await pgPost('/pay/ticket/getAvailablePayTypeList', {
    goodsId: d.goodsId, shopId: d.shopId, orgId: d.orgId,
    imei: d.sn, categoryCode: d.categoryCode,
  }, token, null);
  const arr = (r.json && r.json.code === 0 && Array.isArray(r.json.data)) ? r.json.data : [];
  return arr.map(x => ({
    type: String(x.payType),
    name: x.payTypeName || ('支付方式 ' + x.payType),
    isDefault: x.defaultFlag === 1,
  }));
}

function pgDefaultPayType(list) {
  if (list && list.length) {
    const dft = list.find(x => x.isDefault);
    return dft ? dft.type : list[0].type;
  }
  return PAY_ALIPAY;
}

function pgPayTypeName(list, type) {
  const hit = (list || []).find(x => x.type === String(type));
  if (hit) { return hit.name; }
  if (String(type) === '13') { return '支付宝'; }
  if (String(type) === '15') { return '支付宝免密'; }
  if (String(type) === '32') { return '微信'; }
  if (String(type) === '2') { return '钱包'; }
  return '支付方式 ' + type;
}

/**
 * 解锁失败后的收尾。
 *
 * 早期版本解锁失败就直接 return 了 —— 但订单已经建出来了，留着不管会被结算，
 * 这是真金白银的漏洞。现在：先 order/cancel（服务没开始，撤销不产生费用）。
 */
async function pgRecover(db, orderId, goodsId, token, why) {
  const c = await pgCancelOrder(orderId, token);
  if (c.json && c.json.code === 0) {
    db.pending = null;
    saveDB(db);
    return why + '\n\n已自动撤销订单，不会产生扣费。';
  }
  db.pending = { orderId: orderId, orderNo: '', goodsId: goodsId, at: Date.now() };
  saveDB(db);
  const cm = (c.json && c.json.msg) || c.text || '未知错误';
  return why + '\n\n⚠️ 自动撤销也失败了：' + cm +
    '\n\n订单号 ' + orderId + '\n请到「胖乖生活 → 我的订单」里手动处理。';
}

async function pgCancelOrder(orderId, token) {
  return pgPost('/order/cancel', { orderId: orderId }, token, null);
}

async function pgEndShower(goodsId, orderNo, token) {
  // 字段名实测就是 goodsId + orderNo（orderNo 来自解锁响应，不是下单响应）
  return pgPost('/shower/goods/remoteEnd', { goodsId: goodsId, orderNo: orderNo }, token, null);
}

// ---------------------------------------------------------------
// 7.6 付款 —— 先用后付，结算发生在 remoteEnd 之后
// ---------------------------------------------------------------
// 胖乖生活是先开门、洗完才结算。官方顺序：
//
//     remoteEnd → order/afterPay/creating → payChannelRoute/againPay
//              → pay/unify/prePay{orderNo,payType} → 拉起支付
//
// 旧版脚本只走到 remoteEnd 就收尾了 —— 门关了、账没结。每次洗澡都留一笔
// 未支付订单，攒到服务端用 code 2128（有未支付订单需要完成）把下一次下单
// 整个堵死；而且报错发生在 /trade/preview 上、只回一句 code -1 且 msg 为
// null，界面上看着就是莫名其妙的「订单预览失败」。
//
// 不集成支付宝 SDK（iOS 上是一堆 .ipa 之外的东西），改走「跳转支付」：
// prepayParam 实测是一串支付宝 orderString，塞进 alipays://…&orderSuffix=
// 就能唤起支付宝收银台。
//
// 任何一步失败都有兜底：让用户去官方 App 付款，至少不会卡死。

async function pgPrePay(orderNo, payType, token, risk) {
  return pgPost('/pay/unify/prePay', {
    orderNo: orderNo || '',
    payType: String(payType),
  }, token, risk);
}

/** 查这笔订单是否已支付（data 是 boolean）。 */
async function pgPayStatus(orderNo, payType, token) {
  return pgPost('/order/pay_status', {
    orderNo: orderNo || '',
    payType: String(payType),
  }, token, null);
}

/** 取 data.prepayParam —— 交给支付渠道处理的串。 */
function pgPickPrepayParam(r) {
  const d = (r && r.json && r.json.data) || {};
  return d.prepayParam || '';
}

/** 是不是「有未支付订单」的拦截。 */
function pgIsUnpaidBlock(r) {
  return !!(r && r.json && r.json.code === 2128);
}

/** code 2128 时，服务端会带回堵住的那笔订单号 —— 这是继续下单的唯一出路。 */
function pgIncompleteOrderId(r) {
  if (!pgIsUnpaidBlock(r)) { return ''; }
  const d = r.json.data || {};
  return d.incompleteOrderId || d.incompleteOrderNo || '';
}

/** 从 pending 记录还原风控参数（付款和解锁必须带同一套）。 */
function pgRiskOf(p) {
  return {
    imei: (p && p.sn) || '',
    categoryCode: (p && p.categoryCode) || '',
    lat: PG_LAT,
    lng: PG_LNG,
  };
}

/**
 * 跳去付款。
 * prepayParam 有三种可能形态，按代价从低到高依次试：
 *   1. 本身就是 scheme / 网址；
 *   2. 支付宝 orderString → 用官方 scheme 唤醒支付宝（实测可用）；
 *   3. 退到官方网关，交给支付宝页面处理。
 */
async function pgJumpPay(payType, param) {
  const p = (param || '').trim();

  if (String(payType) === '2') {
    await info('余额支付要在官方 App 里完成',
      '钱包余额支付没法从脚本唤起。\n\n请打开官方「胖乖生活」App 把这笔结掉。');
    return false;
  }
  if (!p) {
    await info('没拿到支付参数',
      '服务端没返回 prepayParam。\n请打开官方「胖乖生活」App 把这笔结掉。');
    return false;
  }

  const tries = [];
  if (/^(alipays|alipayqr):\/\//.test(p) || /^https?:\/\//.test(p)) { tries.push(p); }
  tries.push(ALIPAY_ORDER_SCHEME + encodeURIComponent(p));
  tries.push('https://openapi.alipay.com/gateway.do?' + p);

  for (const u of tries) {
    try {
      Safari.open(u);
      return true;
    } catch (e) { /* 换下一个入口 */ }
  }
  await info('跳转失败', '三个入口都没能唤起支付宝。\n请打开官方「胖乖生活」App 手动付款。');
  return false;
}

/**
 * 结算一笔订单：先要 prepayParam，再让用户去付；付完可以回查确认。
 * 这一笔不付掉，它会一直挡着下一次洗澡。
 */
async function pgSettle(db, orderNo, payType, risk) {
  if (!orderNo) { return; }
  const token = db.tokens.pg;

  const r = await pgPrePay(orderNo, payType, token, risk);
  const param = pgPickPrepayParam(r);
  const code = (r.json && r.json.code);
  const errMsg = r.err ? r.err
    : (code !== 0
      ? ('code ' + code + ((r.json && r.json.msg) ? ' · ' + r.json.msg : ''))
      : '');

  let msg = '订单号：' + orderNo + '\n支付方式：' + pgPayTypeName(db.payTypes, payType);
  if (errMsg) {
    msg += '\n\n服务端没给支付参数：' + errMsg +
      '\n可以直接打开官方「胖乖生活」App 把这笔结掉。';
  } else {
    msg += '\n\n点「去付款」会跳到支付宝完成这一笔。\n' +
      '不付掉的话，它会一直挡着下一次洗澡（服务端 code 2128）。';
  }

  const a = new Alert();
  a.title = '有未支付的洗澡订单';
  a.message = msg;
  a.addAction('去付款');
  a.addAction('已付好了，检查一下');
  a.addCancelAction('稍后');
  const idx = await a.present();

  if (idx === 0) { await pgJumpPay(payType, param); return; }

  if (idx === 1) {
    const s = await pgPayStatus(orderNo, payType, token);
    const paid = !!(s.json && s.json.data === true);
    if (paid) {
      if (db.pending && db.pending.orderNo === orderNo) { db.pending = null; saveDB(db); }
      await info('✅ 已支付', '订单 ' + orderNo + ' 已经结清了。\n下一次洗澡不会被拦。');
    } else {
      await info('还没付成功',
        '服务端说这笔还是未支付。\n\n再点一次「去付款」，' +
        '或者打开官方「胖乖生活」App 结掉它。');
    }
  }
}

/** 结束这一单（按已用时长结算），结束完立刻结算付款。 */
async function showerEndPending(db) {
  if (!db.pending) { await info('没有进行中的订单', ''); return; }
  const p = db.pending;
  if (!p.orderNo) {
    await info('结束不了', '还没拿到订单号（orderNo 要解锁成功才有）。\n可以改用「撤销这一单」。');
    return;
  }
  const r = await pgEndShower(p.goodsId, p.orderNo, db.tokens.pg);
  if (r.json && r.json.code === 0) {
    db.pending = null; saveDB(db);
    // 先用后付：门关上了才结算。这一步不做的话这笔订单会一直挂着，
    // 下一次下单直接被 code 2128 拦住 —— 之前的毛病就在这。
    await pgSettle(db, p.orderNo, p.payType || db.payType || PAY_ALIPAY, pgRiskOf(p));
  } else {
    await info('结束失败', ((r.json && r.json.msg) || r.text) + '\n订单号 ' + p.orderNo);
  }
}

/** 补付一笔未支付的订单（被 2128 拦住、或 pending 里记着的那笔）。 */
async function showerPayUnpaid(db, orderNo, risk) {
  const payType = db.payType || pgDefaultPayType(db.payTypes);
  await pgSettle(db, orderNo, payType, risk);
}

/**
 * 服务端 code 2128：上一笔没付，先把它结掉，否则新订单根本下不出来。
 * 注意被拦时 /goods/normal/skus 返回的 data 是**对象**而不是数组，
 * 所以 skuId 会取空 —— 走不到后面，只能从这里出去。
 */
async function handleUnpaidBlock(db, err) {
  const orderId = (err && err.unpaidOrderId) || '';
  const part = (err && err.partial) || {};
  const risk = {
    imei: part.sn || '',
    categoryCode: part.categoryCode || '',
    lat: PG_LAT,
    lng: PG_LNG,
  };

  let msg = '上一笔洗澡订单还没付，服务端把这次拦下来了。\n\n' +
    (orderId ? '订单号：' + orderId + '\n\n' : '') +
    '把它付掉之后才能开始新的洗澡。';
  if (!(db.payTypes || []).some(x => x.type === '15')) {
    msg += '\n\n（这台机器没有「支付宝免密」渠道。没开免密的话，\n' +
      '结算时扣不动就会变成「待支付」。免密要在官方「胖乖生活」里开通。）';
  }

  const a = new Alert();
  a.title = '有未支付订单';
  a.message = msg;
  a.addAction('去付款');
  a.addAction('稍后');
  if (await a.present() === 0 && orderId) {
    await showerPayUnpaid(db, orderId, risk);
  }
}

/** 撤销这一单（未开始则不扣费） */
async function showerCancelPending(db) {
  if (!db.pending) { await info('没有进行中的订单', ''); return; }
  const p = db.pending;
  const r = await pgCancelOrder(p.orderId, db.tokens.pg);
  if (r.json && r.json.code === 0) {
    db.pending = null; saveDB(db);
    await info('已撤销', '订单 ' + p.orderId + ' 已撤销。');
  } else {
    await info('撤销失败', ((r.json && r.json.msg) || r.text) + '\n订单号 ' + p.orderId);
  }
}

async function showerStart(db, sn) {
  // ⓪ 本地账本硬拦截。
  // 服务端的 shower/goods/lastOrder 实测**靠不住**：真机上订单刚解锁 8 秒、
  // 明明在进行中，它依然返回 data:null。所以只能靠我们自己记的那笔。
  if (db.pending) {
    await info('已经有一笔没结束的订单',
      '订单号 ' + (db.pending.orderNo || db.pending.orderId) +
      '\n\n再开一笔可能重复扣费。请先用菜单里的「结束」或「撤销」处理它。');
    return;
  }

  const token = db.tokens.pg;
  let d;
  try {
    d = await pgResolve(db, sn);
  } catch (e) {
    if (e && e.isUnpaid) { await handleUnpaidBlock(db, e); return; }
    await info('解析失败', String(e.message || e));
    return;
  }

  const risk = { imei: d.sn, categoryCode: d.categoryCode, lat: PG_LAT, lng: PG_LNG };

  const prev = await pgPost('/trade/preview/v4', {
    orgId: d.orgId, goodsId: d.goodsId, shopId: d.shopId,
    categoryCode: d.categoryCode, imei: d.sn, items: pgItems(d),
  }, token, risk);
  const pd = prev.json && prev.json.data;
  const price = pd ? pd.realPrice : null;

  // 取这台机器支持的支付渠道，用官方的默认值（实测是支付宝 13）
  const payTypes = await pgAvailablePayTypes(d, token);
  const payType = db.payType && payTypes.some(x => x.type === db.payType)
    ? db.payType
    : pgDefaultPayType(payTypes);
  db.payTypes = payTypes;
  saveDB(db);

  let msg = (d.orgName || '') + ' · ' + (d.goodsName || '') + '\n' +
    (d.skuName || '') + '  ·  ¥' + (d.skuPrice || '?');
  if (price != null) { msg += '\n实付 ¥' + price; }
  msg += '\n支付方式：' + pgPayTypeName(payTypes, payType);
  if (!payTypes.some(x => x.type === '15')) {
    msg += '\n（列表里没有「支付宝免密」。没开免密的话，这一单结算时\n' +
      '扣不动就会变成「待支付」。免密要在官方「胖乖生活」里开通。）';
  }
  msg += '\n\n**开始前请看一眼机器屏幕**：\n' +
    '显示「请扫码」= 空闲，可以开始；\n' +
    '显示计时 / 余额 / 使用中，说明有人在用，请勿继续。';

  const a = new Alert();
  a.title = '开始洗澡';
  a.message = msg;
  a.addAction('屏幕显示「请扫码」，开始');
  a.addCancelAction('取消');
  if (await a.present() !== 0) { return; }

  // 解锁前预检
  const chk = await pgPost('/trade/check_device_status', { goodsId: d.goodsId }, token, null);
  const cd = chk.json && chk.json.data;
  if (chk.json && chk.json.code === 0 && cd && cd.health === false) {
    await info('已中止', '设备状态异常：' + (cd.faultDesc || '未知原因'));
    return;
  }

  const create = await pgPost('/trade/create', {
    orgId: d.orgId, goodsId: d.goodsId, shopId: d.shopId,
    categoryCode: d.categoryCode, imei: d.sn, items: pgItems(d),
  }, token, risk);

  if (!create.json || create.json.code !== 0) {
    await info('下单失败', (create.json && create.json.msg) || create.text);
    return;
  }
  // trade/create 实测返回的是 { tradeType, tradeNo, orderId } —— 字段名就是 orderId，
  // 没有 orderNo，也没有 iotBOrderNo。
  const cdata = create.json.data || {};
  const orderId = String(cdata.orderId || '');
  if (!orderId) {
    await info('已中止', '下单返回里没有 orderId。带着一笔无法撤销的订单去解锁太危险，' +
      '所以停在这里。\n\n原始返回：\n' + JSON.stringify(create.json));
    return;
  }

  // 立刻落盘，进程被杀也能在下次打开时提醒收尾
  db.pending = {
    orderId: orderId, orderNo: '', goodsId: d.goodsId,
    payType: String(payType), sn: d.sn, categoryCode: d.categoryCode, at: Date.now(),
  };
  saveDB(db);

  let unlock;
  try {
    unlock = await pgPost('/goods/shower/unlock', {
      skuId: String(d.skuId), promotions: '', payType: String(payType),
      iotBOrderNo: orderId,
    }, token, risk);
  } catch (e) {
    await info('解锁异常', await pgRecover(db, orderId, d.goodsId, token,
      '解锁时出错：' + String(e.message || e)));
    return;
  }

  if (!unlock.json || unlock.json.code !== 0) {
    const why = '解锁失败：' + ((unlock.json && unlock.json.msg) || unlock.text);
    await info('解锁失败', await pgRecover(db, orderId, d.goodsId, token, why));
    return;
  }

  // orderNo 只有解锁成功后才拿得到，它才是「结束」要回传的那个号
  const ud = unlock.json.data || {};
  const orderNo = String(ud.orderNo || '');
  db.pending = {
    orderId: orderId, orderNo: orderNo, goodsId: d.goodsId,
    payType: String(payType), sn: d.sn, categoryCode: d.categoryCode, at: Date.now(),
  };
  saveDB(db);

  if (orderNo) { Pasteboard.copy(orderNo); }
  await info('✅ 已解锁',
    (orderNo ? '订单号已复制到剪贴板：\n' + orderNo + '\n\n' : '') +
    '洗完记得回「洗澡」菜单点「结束这一单」——\n' +
    '结束之后会自动跳到支付宝结算这一笔。\n\n' +
    '⚠️ 只结束、不付款的话，这笔订单会一直挂着，\n' +
    '下一次洗澡会被服务端拦住（code 2128）。\n\n' +
    '直接离开的话机器会继续计时。');
}

// ---------------------------------------------------------------
// 8. 设备选择页
// ---------------------------------------------------------------
async function devicesMenu(db, type) {
  const list = devicesOf(db, type);
  const items = list.map(d => ({
    label: (db.current[type] === d.id ? '✓ ' : '   ') + (d.name || d.sub || d.id),
    sub: d.id,
    value: 'pick:' + d.id,
  }));
  items.push({ label: '＋  添加设备', value: 'add' });
  items.push({ label: '←  返回', value: 'back' });

  const title = type === 'water' ? '取水设备' : '洗澡设备';
  const pick = await menu(title, list.length + ' 台', items);
  if (!pick || pick === 'back') { return; }

  if (pick === 'add') {
    const r = await ask('添加设备', '填编号和名字', [
      { hint: type === 'water' ? '设备编号' : '机器 SN' },
      { hint: '名字，例如「3号公寓5楼」' },
    ], '保存');
    if (r && r[0].trim()) {
      const id = type === 'water' ? r[0].trim() : extractSn(r[0]);
      rememberDevice(db, type, id, '', '', '');
      const d = db.devices.find(x => x.type === type && x.id === id);
      if (d && r[1].trim()) { d.name = r[1].trim(); }
      db.current[type] = id;
      saveDB(db);
    }
    return devicesMenu(db, type);
  }

  if (pick.startsWith('pick:')) {
    const id = pick.substring(5);
    const d = db.devices.find(x => x.type === type && x.id === id);
    if (!d) { return devicesMenu(db, type); }

    const act = await menu(d.name || d.sub || id, id, [
      { label: '✓  使用这台', value: 'use' },
      { label: '✏️  重命名', value: 'rename' },
      { label: '🗑  删除', value: 'del' },
      { label: '←  返回', value: 'back' },
    ]);
    if (act === 'use') {
      db.current[type] = id;
      d.usedAt = Date.now();
      saveDB(db);
      return;
    } else if (act === 'rename') {
      const r = await ask('重命名', '', [{ hint: '名字', value: d.name || '' }], '保存');
      if (r) { d.name = r[0].trim(); saveDB(db); }
      return devicesMenu(db, type);
    } else if (act === 'del') {
      db.devices = db.devices.filter(x => !(x.type === type && x.id === id));
      if (db.current[type] === id) { delete db.current[type]; }
      saveDB(db);
      return devicesMenu(db, type);
    }
    return devicesMenu(db, type);
  }
}

// ---------------------------------------------------------------
// 9. 洗衣
// ---------------------------------------------------------------
function openLaundry() {
  const url = 'alipays://platformapi/startapp?appId=' + XIAOLIAN_APPID + LAUNDRY_PAGE;
  Safari.open(url);
}

// ---------------------------------------------------------------
// 10. 主流程
// ---------------------------------------------------------------
async function mainMenu(db) {
  const waterCur = currentDevice(db, 'water');
  const showerCur = currentDevice(db, 'shower');

  const pick = await menu('趵突泉之光', '山东大学趵突泉校区', [
    {
      label: '💧  取水',
      sub: (db.tokens.water ? '已登录' : '未登录') +
        (waterCur ? ' · ' + (waterCur.name || waterCur.sub || waterCur.id) : ''),
      value: 'water',
    },
    {
      label: '🚿  洗澡',
      sub: (db.tokens.pg ? '已登录' : '未登录') +
        (showerCur ? ' · ' + (showerCur.name || showerCur.sub || showerCur.id) : ''),
      value: 'shower',
    },
    { label: '🧺  洗衣', sub: '智慧笑联小程序 · 免登录', value: 'laundry' },
  ]);

  if (!pick) { return 'exit'; }
  if (pick === 'water') { return await waterMenu(db); }
  if (pick === 'shower') { return await showerMenu(db); }
  if (pick === 'laundry') { openLaundry(); return 'main'; }
  return 'exit';
}

async function run() {
  let next = 'main';
  while (next !== 'exit') {
    const db = loadDB();
    if (next === 'main') { next = await mainMenu(db); }
    else if (next === 'water') { next = await waterMenu(db); }
    else if (next === 'shower') { next = await showerMenu(db); }
    else { next = 'exit'; }
  }
}

await run();
