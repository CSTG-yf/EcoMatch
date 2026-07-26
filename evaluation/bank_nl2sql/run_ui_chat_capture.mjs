#!/usr/bin/env node
/**
 * Drive an already authenticated SuperSonic chat page and capture its rendered
 * result tables.  This script never sends requests to the chat API directly:
 * all questions are entered through #chatInput and the page itself performs
 * parse/execute/streaming work.
 *
 * Start a Chromium browser with a remote-debugging endpoint, sign in there,
 * open the bank agent page, then pass its WebSocket endpoint to this runner.
 */

import { createRequire } from 'node:module';
import { readFile, writeFile, mkdir } from 'node:fs/promises';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const scriptDirectory = dirname(fileURLToPath(import.meta.url));
const requireFromWebapp = createRequire(
  resolve(scriptDirectory, '../../webapp/packages/supersonic-fe/package.json')
);
const puppeteer = requireFromWebapp('puppeteer-core');

const sleep = milliseconds => new Promise(resolve => setTimeout(resolve, milliseconds));

function parseArgs(argv) {
  const values = {};
  for (let index = 0; index < argv.length; index += 1) {
    const key = argv[index];
    if (!key.startsWith('--')) {
      throw new Error(`Unexpected argument: ${key}`);
    }
    const value = argv[index + 1];
    if (!value || value.startsWith('--')) {
      throw new Error(`Missing value for ${key}`);
    }
    values[key.slice(2)] = value;
    index += 1;
  }
  for (const required of ['dataset', 'page-url', 'agent-id', 'output']) {
    if (!values[required]) {
      throw new Error(`Missing required --${required}`);
    }
  }
  if (!values['browser-ws-endpoint'] && !values['browser-debug-url']) {
    throw new Error('Provide --browser-debug-url or --browser-ws-endpoint');
  }
  return {
    browserWsEndpoint: values['browser-ws-endpoint'],
    browserDebugUrl: values['browser-debug-url'],
    dataset: values.dataset,
    pageUrl: values['page-url'],
    agentId: values['agent-id'],
    output: values.output,
    split: values.split || 'dev',
    recordId: values['record-id'],
    timeoutSeconds: Number(values['timeout-seconds'] || '45'),
    maxRecords: values['max-records'] ? Number(values['max-records']) : undefined,
  };
}

async function resolveBrowserWebSocketEndpoint(args) {
  if (args.browserWsEndpoint) {
    return args.browserWsEndpoint;
  }
  const response = await fetch(`${args.browserDebugUrl.replace(/\/$/, '')}/json/version`);
  if (!response.ok) {
    throw new Error('The browser debugging endpoint did not respond');
  }
  const details = await response.json();
  if (!details || typeof details.webSocketDebuggerUrl !== 'string') {
    throw new Error('The browser debugging endpoint did not provide a WebSocket URL');
  }
  return details.webSocketDebuggerUrl;
}

async function readJsonLines(path) {
  const content = await readFile(path, 'utf8');
  return content
    .split(/\r?\n/)
    .filter(Boolean)
    .map(line => JSON.parse(line));
}

async function waitForNewOutcome(page, question, previousMessageCount, timeoutMs) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    const outcome = await page.evaluate(({ expectedQuestion, previousCount }) => {
      const messageItems = Array.from(document.querySelectorAll('#messageContainer [class*="messageItem"]')).filter(
        item => item.offsetParent !== null
      );
      if (messageItems.length <= previousCount) {
        return false;
      }
      const currentItem = messageItems[messageItems.length - 1];
      if (!currentItem.textContent?.includes(expectedQuestion)) {
        return false;
      }
      if (currentItem.querySelector('[data-testid="ui-chat-result-table"]')) {
        return 'table';
      }
      // Failure labels can be present in hidden reusable UI nodes.  A visible
      // result table is the only positive terminal state; otherwise the caller
      // records a terminal failure after the bounded page wait expires.
      return false;
    }, { expectedQuestion: question, previousCount: previousMessageCount });
    if (outcome) {
      return outcome;
    }
    await sleep(300);
  }
  throw new Error(`Timed out waiting ${timeoutMs}ms for the page result table`);
}

async function readVisibleTable(page, question) {
  return page.evaluate(expectedQuestion => {
    const messageItems = Array.from(document.querySelectorAll('#messageContainer [class*="messageItem"]')).filter(
      item => item.offsetParent !== null
    );
    const currentItem = [...messageItems].reverse().find(item => item.textContent?.includes(expectedQuestion));
    const tables = Array.from(currentItem?.querySelectorAll('[data-testid="ui-chat-result-table"]') || []);
    const root = tables[tables.length - 1];
    if (!root) {
      return null;
    }
    const headers = Array.from(root.querySelectorAll('thead th')).map(
      header => header.getAttribute('data-biz-name') || header.textContent?.trim() || ''
    );
    const formats = Array.from(root.querySelectorAll('thead th')).map(header => ({
      bizName: header.getAttribute('data-biz-name') || header.textContent?.trim() || '',
      type: header.getAttribute('data-format-type') || '',
      needMultiply100: header.getAttribute('data-need-multiply-100') === 'true',
    }));
    const rows = Array.from(root.querySelectorAll('tbody tr')).map(row =>
      Array.from(row.querySelectorAll('td')).map(cell => cell.textContent?.trim() || '')
    );
    const next = root.querySelector('.ant-pagination-next');
    return {
      headers,
      formats,
      rows,
      hasNext: Boolean(next && !next.classList.contains('ant-pagination-disabled')),
    };
  }, question);
}

async function collectAllVisiblePages(page, question, timeoutMs) {
  const first = await readVisibleTable(page, question);
  if (!first) {
    return null;
  }
  const rows = [...first.rows];
  while ((await readVisibleTable(page, question))?.hasNext) {
    const before = JSON.stringify((await readVisibleTable(page, question))?.rows || []);
    const clicked = await page.evaluate(expectedQuestion => {
      const messageItems = Array.from(document.querySelectorAll('#messageContainer [class*="messageItem"]')).filter(
        item => item.offsetParent !== null
      );
      const currentItem = [...messageItems].reverse().find(item => item.textContent?.includes(expectedQuestion));
      const tables = Array.from(currentItem?.querySelectorAll('[data-testid="ui-chat-result-table"]') || []);
      const root = tables[tables.length - 1];
      const next = root?.querySelector('.ant-pagination-next');
      if (!(next instanceof HTMLElement) || next.classList.contains('ant-pagination-disabled')) {
        return false;
      }
      next.click();
      return true;
    }, question);
    if (!clicked) {
      break;
    }
    const deadline = Date.now() + timeoutMs;
    let changed = false;
    while (Date.now() < deadline) {
      const current = await readVisibleTable(page, question);
      if (JSON.stringify(current?.rows || []) !== before) {
        changed = true;
        break;
      }
      await sleep(300);
    }
    if (!changed) {
      throw new Error(`Timed out waiting ${timeoutMs}ms for the next result page`);
    }
    const pageData = await readVisibleTable(page, question);
    rows.push(...(pageData?.rows || []));
  }
  return { headers: first.headers, formats: first.formats, rows };
}

async function fillChatInput(input, value) {
  await input.evaluate((element, nextValue) => {
    const setter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')?.set;
    if (!setter) {
      throw new Error('The browser does not expose the native input value setter');
    }
    setter.call(element, nextValue);
    element.dispatchEvent(new Event('input', { bubbles: true }));
  }, value);
}

async function captureRecord(page, record, timeoutMs) {
  const previousMessageCount = await page.$$eval(
    '#messageContainer [class*="messageItem"]',
    nodes => nodes.filter(node => node.offsetParent !== null).length
  );
  const input = await page.$('#chatInput');
  if (!input) {
    throw new Error('The chat page did not expose #chatInput');
  }
  await fillChatInput(input, record.question);
  await page.waitForFunction(
    () => Boolean(document.querySelector('[class*="composerInputWrapper"] [class*="sendBtnActive"]')),
    { timeout: timeoutMs }
  );
  // ChatFooter sends from its React onKeyDown handler.  Pressing Enter is the
  // same user-visible action as submitting from the keyboard and avoids a
  // synthetic DOM click bypassing that handler in Edge.
  await input.press('Enter');
  try {
    const outcome = await waitForNewOutcome(page, record.question, previousMessageCount, timeoutMs);
    const table = await collectAllVisiblePages(page, record.question, timeoutMs);
    if (!table) {
      return { id: record.id, state: 'ui_terminal_failure', headers: [], rows: [] };
    }
    return { id: record.id, state: 'done', headers: table.headers, rows: table.rows, formats: table.formats };
  } catch (error) {
    return {
      id: record.id,
      state: 'ui_terminal_failure',
      headers: [],
      rows: [],
      error: error instanceof Error ? error.message : String(error),
    };
  }
}

async function openRequestedAgentPage(page, args, timeoutMs) {
  const target = new URL(args.pageUrl);
  const current = new URL(page.url());
  const isRequestedPage =
    current.pathname === target.pathname && current.searchParams.get('agentId') === target.searchParams.get('agentId');
  if (!isRequestedPage) {
    await page.goto(args.pageUrl, { waitUntil: 'domcontentloaded', timeout: timeoutMs });
  }
  try {
    await page.waitForFunction(
      expectedAgentId => {
        const url = new URL(window.location.href);
        return url.pathname.endsWith('/webapp/chat') && url.searchParams.get('agentId') === expectedAgentId;
      },
      { timeout: timeoutMs },
      args.agentId
    );
  } catch {
    throw new Error(
      `The browser did not remain on the requested chat page (${args.pageUrl}). ` +
        'Open the bank agent chat page in the authenticated evaluation browser, then run again.'
    );
  }
  await page.waitForSelector('#chatInput', { timeout: timeoutMs });
}

async function main() {
  const args = parseArgs(process.argv.slice(2));
  if (!['train', 'dev'].includes(args.split)) {
    throw new Error('The page runner only permits --split train or dev; frozen test is intentionally blocked');
  }
  const allRecords = await readJsonLines(resolve(args.dataset, `${args.split}.jsonl`));
  if (args.maxRecords !== undefined && (!Number.isInteger(args.maxRecords) || args.maxRecords < 1)) {
    throw new Error('--max-records must be a positive integer');
  }
  let records = args.recordId === undefined
    ? allRecords
    : allRecords.filter(record => record.id === args.recordId);
  if (args.recordId !== undefined && records.length !== 1) {
    throw new Error(`No unique record found for --record-id ${args.recordId}`);
  }
  if (args.maxRecords !== undefined) {
    records = records.slice(0, args.maxRecords);
  }
  const browser = await puppeteer.connect({
    browserWSEndpoint: await resolveBrowserWebSocketEndpoint(args),
    // Edge exposes a non-web nurturing tab in this profile.  Keeping the
    // existing viewport avoids Puppeteer trying to emulate device metrics on it.
    defaultViewport: null,
  });
  const timeoutMs = args.timeoutSeconds * 1000;
  try {
    const items = [];
    for (const record of records) {
      // A new page shares the authenticated browser profile but starts with a
      // blank React chat state, so each evaluation question is context-isolated.
      const page = await browser.newPage();
      try {
        await openRequestedAgentPage(page, args, timeoutMs);
        items.push(await captureRecord(page, record, timeoutMs));
      } finally {
        await page.close({ runBeforeUnload: false });
      }
    }
    const report = {
      run: {
      split: args.split,
        recordId: args.recordId,
        agentId: Number(args.agentId),
        pageUrl: args.pageUrl,
        captureMethod: 'authenticated-ui-puppeteer',
      },
      recordCount: items.length,
      sourceRecordCount: allRecords.length,
      items,
    };
    await mkdir(dirname(args.output), { recursive: true });
    await writeFile(args.output, `${JSON.stringify(report, null, 2)}\n`, 'utf8');
  } finally {
    // Puppeteer may leave Edge's DevTools socket pending after a capture has
    // already been written. Disconnect without awaiting that socket so the
    // one-shot runner can return its report deterministically.
    browser.disconnect();
  }
}

main().then(
  () => process.exit(0),
  error => {
    process.stderr.write(`${error.message}\n`);
    process.exit(1);
  }
);
