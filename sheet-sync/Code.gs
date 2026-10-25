/**
 * Risk Tracker Google Sheets sync endpoint.
 *
 * Setup:
 * 1. Open your Google Sheet -> Extensions -> Apps Script.
 * 2. Paste this file and replace TOKEN with a long, unique secret.
 * 3. Save, then Deploy -> New deployment -> Web app.
 * 4. Execute as: Me. Who has access: Anyone (if available and appropriate).
 * 5. Copy the deployed /exec URL into Risk Tracker's Sync tab and enter the same token.
 *
 * The Android app sends POST requests with action "pull" or "push".
 * Expected sheet tab: Daily_PNL. Data starts on row 5.
 * Columns A, B and D are written; A:K are copied from the previous row
 * so existing formulas and formatting carry down.
 */
const TOKEN = 'CHANGE_ME_TO_A_LONG_RANDOM_STRING';
const SHEET_NAME = 'Daily_PNL';
const FIRST_ROW = 5;
const LAST_COL = 11; // A:K

// Browser health check only. Actual sync uses POST.
function doGet(e) {
  return ContentService
    .createTextOutput('Risk Tracker sync endpoint is running. Use the Android app to sync.')
    .setMimeType(ContentService.MimeType.TEXT);
}

function doPost(e) {
  const lock = LockService.getScriptLock();
  try {
    if (!e || !e.postData || !e.postData.contents) {
      throw new Error('Missing request body');
    }
    if (!TOKEN || TOKEN === 'CHANGE_ME_TO_A_LONG_RANDOM_STRING') {
      throw new Error('Configure TOKEN in Apps Script before syncing');
    }

    const body = JSON.parse(e.postData.contents);
    if (body.token !== TOKEN) {
      return out_({ ok: false, error: 'Invalid token' });
    }

    const spreadsheet = SpreadsheetApp.getActiveSpreadsheet();
    const sheet = spreadsheet.getSheetByName(SHEET_NAME);
    if (!sheet) {
      return out_({ ok: false, error: 'Sheet "' + SHEET_NAME + '" not found' });
    }

    lock.waitLock(20000);
    try {
      if (body.action === 'push') {
        if (!Array.isArray(body.entries)) throw new Error('entries must be an array');
        push_(sheet, body.entries);
      } else if (body.action !== 'pull') {
        throw new Error('Unsupported action: ' + body.action);
      }

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
    return out_({ ok: false, error: String(err && err.message ? err.message : err) });
  }
}

function readEntries_(sheet) {
  const timezone = sheet.getParent().getSpreadsheetTimeZone();
  const last = sheet.getLastRow();
  if (last < FIRST_ROW) return [];

  const values = sheet.getRange(FIRST_ROW, 1, last - FIRST_ROW + 1, 4).getValues();
  const result = [];

  for (let i = 0; i < values.length; i++) {
    const row = values[i];
    if (row[1] === '' || row[1] == null) break;
    if (!(row[1] instanceof Date)) {
      throw new Error('Invalid date in Daily_PNL row ' + (FIRST_ROW + i));
    }
    if (row[2] === '' || row[3] === '') break;

    result.push({
      date: Utilities.formatDate(row[1], timezone, 'd/M/yyyy'),
      balance: Number(row[2]),
      pnl: Number(row[3])
    });
  }
  return result;
}

function push_(sheet, entries) {
  const timezone = sheet.getParent().getSpreadsheetTimeZone();

  for (const entry of entries) {
    if (!entry || typeof entry !== 'object') throw new Error('Invalid P&L entry');
    if (typeof entry.date !== 'string') throw new Error('Entry date must be a string');

    const match = entry.date.match(/^(\d{1,2})\/(\d{1,2})\/(\d{4})$/);
    if (!match) throw new Error('Invalid date "' + entry.date + '". Expected d/M/yyyy');

    const day = Number(match[1]);
    const month = Number(match[2]);
    const year = Number(match[3]);
    const date = new Date(year, month - 1, day);
    if (date.getFullYear() !== year || date.getMonth() !== month - 1 || date.getDate() !== day) {
      throw new Error('Invalid calendar date: ' + entry.date);
    }

    const balance = Number(entry.balance);
    const pnl = Number(entry.pnl);
    if (!Number.isFinite(balance) || !Number.isFinite(pnl)) {
      throw new Error('Balance and PNL must be valid numbers');
    }

    const existing = readEntries_(sheet);
    const count = existing.length;
    if (count === 0) {
      throw new Error('Add the first day manually in the sheet before syncing');
    }

    const dateKey = Utilities.formatDate(date, timezone, 'd/M/yyyy');
    if (existing.some(function (item) { return item.date === dateKey; })) {
      continue; // Do not append a date already present.
    }

    const lastRow = FIRST_ROW + count - 1;
    const newRow = lastRow + 1;

    sheet.getRange(lastRow, 1, 1, LAST_COL)
      .copyTo(sheet.getRange(newRow, 1, 1, LAST_COL));
    sheet.getRange(newRow, 1).setValue(count + 1);
    sheet.getRange(newRow, 2).setValue(date);
    sheet.getRange(newRow, 4).setValue(pnl);

    // Treat the difference from yesterday's closing balance as a deposit/withdrawal.
    const previous = existing[count - 1];
    const gap = balance - (previous.balance + previous.pnl);
    if (Math.abs(gap) > 0.005) {
      sheet.getRange(newRow, 3).setFormula(
        '=C' + lastRow + '+D' + lastRow + '+(' + gap.toFixed(2) + ')'
      );
    }
  }
}

function out_(object) {
  return ContentService.createTextOutput(JSON.stringify(object))
    .setMimeType(ContentService.MimeType.JSON);
}
