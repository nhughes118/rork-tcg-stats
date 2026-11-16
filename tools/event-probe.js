/**
 * Diagnostic: loads Pokémon's Event Locator in headless Chromium the same way the
 * apps do (force AreEventsBeingSearched/IsShowEvents, reload), captures the real
 * GetEventList response, and prints its datetime fields.
 *
 * Usage: bun tools/event-probe.js "[url]"   (defaults to a Manhattan coordinate)
 */
const { chromium } = require('playwright');
const fs = require('fs');
const os = require('os');

const URL =
  process.argv[2] ||
  'https://events.pokemon.com/EventLocator/Home?locale=en-US&latitude=40.7484&longitude=-73.9967&range=25&startdate=2026-11-16&iskm=false&filters=';
const OUT = os.tmpdir() + '/eventlist.json';

const HOOK = `
(function(){
  if (window.__tcgHooked) return; window.__tcgHooked = true;
  var D = String.fromCharCode(36), MARK = D + 'ClientVars' + D, FORCE = ['AreEventsBeingSearched', 'IsShowEvents'];
  var SP = Storage.prototype, og = SP.getItem, osi = SP.setItem;
  function forced(k){ k = String(k); var i = k.indexOf(MARK); if (i < 0) return false; return FORCE.indexOf(k.substring(i + MARK.length)) >= 0; }
  function truthy(v){ if (v === 'True' || v === 'False') return 'True'; if (v === '1' || v === '0') return '1'; return 'true'; }
  try { SP.getItem = function(k){ var v = og.apply(this, arguments); try { if (forced(k)) return truthy(v); } catch(e){} return v; }; } catch(e){}
  function prime(){ try { var s = window.localStorage, pre = null; for (var i = 0; i < s.length; i++){ var k = s.key(i), j = k.indexOf(MARK); if (j >= 0 && k.indexOf('EventLocator') >= 0){ pre = k.substring(0, j + MARK.length); break; } } if (!pre) return false; FORCE.forEach(function(n){ var v = og.call(s, pre + n); if (v !== truthy(v)) osi.call(s, pre + n, truthy(v)); }); return true; } catch(e){ return false; } }
  window.__tcgPrime = prime;
})();
`;

(async () => {
  const browser = await chromium
    .launch()
    .catch(() => chromium.launch({ executablePath: '/usr/local/bin/chromium' }));
  const ctx = await browser.newContext({
    userAgent:
      'Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36',
  });
  const page = await ctx.newPage();
  await page.addInitScript(HOOK);

  const bodies = [];
  page.on('response', async (r) => {
    const u = r.url();
    if (/screenservices|GetEventList|GetLocations/i.test(u)) {
      console.log('NET', r.status(), u.substring(0, 140));
    }
    if (/GetEventList/i.test(u)) {
      try {
        bodies.push({ url: r.url(), status: r.status(), text: await r.text() });
        console.log('captured GetEventList', r.status(), r.url().substring(0, 100));
      } catch (e) {
        console.log('captured but body read failed:', e.message);
      }
    }
  });
  page.on('console', (m) => console.log('PAGE:', m.text().substring(0, 200)));
  page.on('requestfailed', (r) => console.log('FAILED', r.failure()?.errorText, r.url().substring(0, 120)));

  // First load: let the site establish its storage keys, then prime and reload (what the app does).
  await page.goto(URL, { waitUntil: 'domcontentloaded', timeout: 60000 });
  await page.waitForTimeout(8000);
  const primed = await page.evaluate(() => (window.__tcgPrime ? window.__tcgPrime() : false));
  console.log('primed:', primed);
  await page.reload({ waitUntil: 'domcontentloaded' });

  const start = Date.now();
  while (Date.now() - start < 45000 && bodies.length === 0) await page.waitForTimeout(1000);
  await page.waitForTimeout(4000);
  try {
    const title = await page.title();
    console.log('page title:', title);
    const stored = await page.evaluate(() => Object.keys(localStorage).filter((k) => k.includes('EventLocator')).length);
    console.log('EventLocator storage keys:', stored);
  } catch (e) {}

  if (bodies.length === 0) {
    console.log('NO GetEventList RESPONSE CAPTURED in 45s after reload');
    await page.screenshot({ path: os.tmpdir() + '/eventlocator.png', fullPage: false });
    console.log('screenshot saved to ' + os.tmpdir() + '/eventlocator.png');
    await browser.close();
    process.exit(1);
  }

  fs.writeFileSync(OUT, bodies.map((b) => b.text).join('\n---RESPONSE---\n'));
  for (let i = 0; i < bodies.length; i++) {
    try {
      const j = JSON.parse(bodies[i].text);
      const s = JSON.stringify(j);
      console.log(`body ${i}: ${s.length} bytes, status ${bodies[i].status}`);
      const dates = s.match(/"[^"]*(?:start|date|time)[^"]*"\s*:\s*"[^"]*"/gi) || [];
      console.log([...new Set(dates)].slice(0, 25).join('\n'));
    } catch (e) {
      console.log(`body ${i}: not JSON, ${bodies[i].text.length} bytes, status ${bodies[i].status}`);
      console.log(bodies[i].text.substring(0, 300));
    }
  }
  console.log('full response saved to', OUT);
  await browser.close();
})();
