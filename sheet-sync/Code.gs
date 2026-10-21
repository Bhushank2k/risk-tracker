/**
 * Risk Tracker sync endpoint.
 *
 * Setup: open your Google Sheet -> Extensions -> Apps Script -> paste this file,
 * change TOKEN below to a long random string, then Deploy -> New deployment ->
 * type "Web app", Execute as "Me", Who has access "Anyone" -> Deploy.
 * Copy the Web app URL into the app's Sync tab together with the same token.
 *
 * The script only writes columns A (Sr), B (Date), D (Daily PNL) of the Daily_PNL tab,
 * and copies the row above so your formulas (balance, WL %, INR, etc.) carry down.
 */
const TOKEN = 'CHANGE_ME_TO_A_LONG_RANDOM_STRING';
const SHEET_NAME = 'Daily_PNL';
const FIRST_ROW = 5;      // first data row (Sr. No. 1)
const LAST_COL = 11;      // A..K are copied from the row above

function doPost(e) {
  try {
    const body = JSON.parse(e.postData.contents);
    if (TOKEN === 'CHANGE_ME_TO_A_LONG_RANDOM_STRING' || body.token !== TOKEN) {
      return out_({ ok: false, error: 'Invalid token' });
    }
    const sheet = SpreadsheetApp.getActive().getSheetByName(SHEET_NAME);
    if (!sheet) return out_({ ok: false, error: 'Sheet "' + SHEET_NAME + '" not found' });

    const lock = LockService.getScriptLock();
    lock.waitLock(20000);
    try {
      if (body.action === 'push') push_(sheet, body.entries || []);
      SpreadsheetApp.flush();
      return out_({
        ok: true,
        inr: Number(sheet.getRange('G3').getValue()) || 0,
        entries: readEntries_(sheet)
      });
    } finally {
      lock.releaseLock();
    }
  } catch (err) {
    return out_({ ok: false, error: String(err) });
  }
}

function readEntries_(sheet) {
  const tz = SpreadsheetApp.getActive().getSpreadsheetTimeZone();
  const last = sheet.getLastRow();
  if (last < FIRST_ROW) return [];
  const vals = sheet.getRange(FIRST_ROW, 1, last - FIRST_ROW + 1, 4).getValues();
  const result = [];
  for (const r of vals) {
    if (!(r[1] instanceof Date) || r[2] === '' || r[3] === '') break;
    result.push({
      date: Utilities.formatDate(r[1], tz, 'd/M/yyyy'),
      balance: Number(r[2]),
      pnl: Number(r[3])
    });
  }
  return result;
}

function push_(sheet, entries) {
  const tz = SpreadsheetApp.getActive().getSpreadsheetTimeZone();
  entries.forEach(function (e) {
    const existing = readEntries_(sheet);
    const n = existing.length;
    if (n === 0) throw new Error('Add the first day manually in the sheet before syncing');
    const lastRow = FIRST_ROW + n - 1;
    const row = lastRow + 1;

    sheet.getRange(lastRow, 1, 1, LAST_COL).copyTo(sheet.getRange(row, 1, 1, LAST_COL));
    sheet.getRange(row, 1).setValue(n + 1);
    sheet.getRange(row, 2).setValue(Utilities.parseDate(e.date, tz, 'd/M/yyyy'));
    sheet.getRange(row, 4).setValue(Number(e.pnl));

    // If the opening balance isn't yesterday's closing, treat the gap as a deposit/withdrawal.
    const prev = existing[n - 1];
    const gap = Number(e.balance) - (prev.balance + prev.pnl);
    if (Math.abs(gap) > 0.005) {
      sheet.getRange(row, 3).setFormula('=C' + lastRow + '+D' + lastRow + '+(' + gap.toFixed(2) + ')');
    }
  });
}

function out_(obj) {
  return ContentService.createTextOutput(JSON.stringify(obj))
    .setMimeType(ContentService.MimeType.JSON);
}
