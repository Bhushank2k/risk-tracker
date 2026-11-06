const corsHeaders = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Methods": "POST, OPTIONS",
  "Access-Control-Allow-Headers": "Content-Type, Authorization",
  "Content-Type": "application/json; charset=utf-8",
};

function json(body, status = 200) {
  return new Response(JSON.stringify(body), { status, headers: corsHeaders });
}

function outputText(response) {
  if (typeof response.output_text === "string") return response.output_text.trim();
  const chunks = [];
  for (const item of response.output || []) {
    for (const part of item.content || []) {
      if (part.type === "output_text" && typeof part.text === "string") chunks.push(part.text);
    }
  }
  return chunks.join("\n").trim();
}

export default {
  async fetch(request, env) {
    if (request.method === "OPTIONS") return new Response(null, { headers: corsHeaders });
    if (request.method !== "POST") return json({ error: "Use POST /analyze." }, 405);

    const expected = env.APP_ACCESS_TOKEN;
    const supplied = request.headers.get("Authorization") || "";
    if (!expected || supplied !== "Bearer " + expected) return json({ error: "Unauthorized AI backend request." }, 401);
    if (!env.OPENAI_API_KEY) return json({ error: "Backend is missing its OpenAI API key secret." }, 503);

    let idea;
    try { idea = await request.json(); }
    catch { return json({ error: "Request body must be valid JSON." }, 400); }

    if (!idea || typeof idea.symbol !== "string" ||
        !["LONG", "SHORT", "WAIT"].includes(idea.side) ||
        !Number.isFinite(idea.entry) || !Number.isFinite(idea.stop) ||
        !Number.isFinite(idea.target1) || !Number.isFinite(idea.target2)) {
      return json({ error: "Trade setup payload is incomplete or invalid." }, 400);
    }

    const compact = {
      symbol: String(idea.symbol).slice(0, 30), side: idea.side,
      score: Number(idea.score) || 0, setup: String(idea.setup || "").slice(0, 120),
      horizon: String(idea.horizon || "").slice(0, 80), lastPrice: Number(idea.lastPrice) || 0,
      entry: idea.entry, stop: idea.stop, target1: idea.target1, target2: idea.target2,
      entryTriggered: Boolean(idea.entryTriggered), rsi15m: Number(idea.rsi15m) || 0,
      volumeRatio: Number(idea.volumeRatio) || 0, bias4h: String(idea.bias4h || ""),
      bias1h: String(idea.bias1h || ""), bias5m: String(idea.bias5m || ""),
      spreadBps: idea.spreadBps, bookImbalance: idea.bookImbalance, fundingRate: idea.fundingRate,
      openInterestChangePct: idea.openInterestChangePct, warning: String(idea.warning || "").slice(0, 500),
      reasons: Array.isArray(idea.reasons) ? idea.reasons.slice(0, 12).map(x => String(x).slice(0, 240)) : [],
      historicalReplayTrades: Number(idea.historicalReplayTrades) || 0,
      historicalReplayWins: Number(idea.historicalReplayWins) || 0,
      historicalReplayAverageR: Number(idea.historicalReplayAverageR) || 0,
    };

    const prompt = [
      "Explain this existing Binance USDT-M futures scanner setup to an experienced retail trader.",
      "This is explanation only, not a signal-generation task.",
      "Do not change, recalculate, optimize, or recommend different entry, stop-loss, or target levels.",
      "Do not claim a trade is profitable or predict price. Distinguish observed inputs from interpretation.",
      "Mention timeframe agreement or conflicts, momentum/volume, liquidity/microstructure caveats, and supplied warning.",
      "If sample counts are small, say historical replay is limited and not predictive.",
      "Use: Setup summary; What supports it; What conflicts or could invalidate it; Risk checklist.",
      "If side is WAIT, explicitly say the scanner has no directional setup. Do not encourage entry.",
      "Keep under 220 words. Avoid personalized financial advice.",
      "Trade setup JSON:", JSON.stringify(compact),
    ].join("\n");

    let upstream;
    try {
      upstream = await fetch("https://api.openai.com/v1/responses", {
        method: "POST",
        headers: { "Authorization": "Bearer " + env.OPENAI_API_KEY, "Content-Type": "application/json" },
        body: JSON.stringify({
          model: env.OPENAI_MODEL || "gpt-4.1-mini",
          instructions: "You are a cautious market-data explainer. Never issue or modify trading signals, and never execute orders.",
          input: prompt,
          max_output_tokens: 420,
        }),
      });
    } catch {
      return json({ error: "Could not reach the AI provider. Try again later." }, 502);
    }

    const raw = await upstream.text();
    if (!upstream.ok) return json({ error: "AI provider request failed (" + upstream.status + "). Check backend billing, model access and server logs." }, 502);
    let result;
    try { result = JSON.parse(raw); }
    catch { return json({ error: "AI provider returned an unreadable response." }, 502); }
    const analysis = outputText(result);
    if (!analysis) return json({ error: "AI provider returned no explanation." }, 502);
    return json({ analysis: analysis.slice(0, 3500) });
  },
};
