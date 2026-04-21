// CN v1.1 Apps Script — replaces the v1.0 doPost handler.
//
// Contract change from v1.0:
//  - Payload is now a JSON ARRAY of row objects (one element per target per
//    device per 15-min window). v1.0 was a single JSON object.
//  - 40-column schema. v1.0 column names `avg_ping_ms` etc. are renamed to
//    `avg_rtt_ms`. `network_type` is renamed to `network_type_dominant`.
//  - Response unchanged: `{"status":"ok","rows_appended":N}` on success, or
//    `{"status":"error","message":"..."}` on failure. SheetsUploader's
//    dual-gate (HTTP 2xx + body.status=="ok") still works unchanged.
//
// Deploy procedure:
//   1. Paste this over the existing script bound to the CN Sheet.
//   2. Optionally wipe row 1 of Sheet1 so new v1.1 headers are auto-written
//      on the first POST. (If you leave v1.0 rows, they stay in place but
//      new rows will have the v1.1 column order — misaligned unless Sheet
//      is wiped. User explicitly OK'd wiping.)
//   3. Deploy → New Deployment (web app, "Anyone with the link").
//      Keep the /exec URL matching Constants.WEBHOOK_URL.
//   4. Curl-test:
//        curl -L -X POST -H "Content-Type: application/json" \
//             -d '[{"window_start":"2026-04-21T10:00:00Z","target":"smartflo","avg_rtt_ms":34.5,"samples_count":900}]' \
//             "$WEBHOOK_URL"
//      Expect: {"status":"ok","rows_appended":1}

const SHEET_NAME = "Sheet1";

const HEADERS = [
  // identity
  "window_start", "user_id", "device_model", "android_sdk", "oem_skin", "app_version",
  // per-target
  "target", "gateway_ip", "unreachable_target",
  // RTT metrics
  "avg_rtt_ms", "min_rtt_ms", "max_rtt_ms",
  "p50_rtt_ms", "p95_rtt_ms", "p99_rtt_ms",
  "jitter_ms", "packet_loss_pct",
  // sample counts
  "samples_count", "reachable_samples_count", "max_rtt_offset_sec",
  // Wi-Fi aggregates (duplicated across per-target rows in a flush)
  "gaps_count", "bssid_changes_count", "ssid_changes_count",
  "rssi_min", "rssi_avg", "rssi_max",
  "primary_bssid", "primary_ssid", "primary_frequency_mhz", "primary_link_speed_mbps",
  "current_bssid", "current_rssi",
  "network_type_dominant", "vpn_active",
  // scan context (once per 15-min window)
  "visible_aps_count", "best_available_rssi", "sticky_client_gap_db",
  // operational telemetry
  "duty_cycle_pct", "flush_seq", "retain_merged_count"
];

function doPost(e) {
  try {
    const parsed = JSON.parse(e.postData.contents);
    if (!Array.isArray(parsed)) {
      return jsonResponse({
        status: "error",
        message: "expected JSON array of row objects, got " + typeof parsed
      });
    }

    const sheet = SpreadsheetApp.getActiveSpreadsheet().getSheetByName(SHEET_NAME);
    if (!sheet) {
      return jsonResponse({
        status: "error",
        message: "sheet '" + SHEET_NAME + "' not found"
      });
    }

    // Auto-write the 40-column header row if the sheet is empty.
    if (sheet.getLastRow() === 0) {
      sheet.appendRow(HEADERS);
    }

    // Build row arrays by looking up each header in each object. Missing /
    // null fields become blank cells (safer than "null" strings in the Sheet).
    const rows = parsed.map(function (row) {
      return HEADERS.map(function (h) {
        const v = row[h];
        return (v === undefined || v === null) ? "" : v;
      });
    });

    if (rows.length > 0) {
      // Batch append — one setValues() call for all rows, much faster than
      // N appendRow() calls when batching 4+ targets per device per flush.
      sheet.getRange(sheet.getLastRow() + 1, 1, rows.length, HEADERS.length)
           .setValues(rows);
    }

    return jsonResponse({ status: "ok", rows_appended: rows.length });
  } catch (err) {
    return jsonResponse({ status: "error", message: err.toString() });
  }
}

function doGet(e) {
  return ContentService.createTextOutput(
    "Pulseboard CN v1.1 webhook is live. Expects JSON array payloads."
  );
}

function jsonResponse(obj) {
  return ContentService
    .createTextOutput(JSON.stringify(obj))
    .setMimeType(ContentService.MimeType.JSON);
}
