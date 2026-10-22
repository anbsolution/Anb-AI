import "jsr:@supabase/functions-js/edge-runtime.d.ts";

const corsHeaders = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type",
  "Access-Control-Allow-Methods": "POST, OPTIONS",
};

function json(data: unknown, status = 200) {
  return new Response(JSON.stringify(data), {
    status,
    headers: { ...corsHeaders, "Content-Type": "application/json" },
  });
}

async function getAuthenticatedUserId(req: Request): Promise<string | null> {
  const authorization = req.headers.get("Authorization") ?? "";
  const supabaseUrl = Deno.env.get("SUPABASE_URL");
  const publishable = Deno.env.get("SUPABASE_ANON_KEY") || (() => {
    try { return JSON.parse(Deno.env.get("SUPABASE_PUBLISHABLE_KEYS") || "{}")["default"]; } catch { return ""; }
  })();
  if (!supabaseUrl || !publishable || !authorization.startsWith("Bearer ")) return null;
  try {
    const response = await fetch(`${supabaseUrl}/auth/v1/user`, {
      headers: { "apikey": publishable, "Authorization": authorization },
    });
    if (!response.ok) return null;
    const user = await response.json();
    return typeof user?.id === "string" ? user.id : null;
  } catch {
    return null;
  }
}

Deno.serve(async (req: Request) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: corsHeaders });
  if (req.method !== "POST") return json({ error: "Use POST." }, 405);
  const userId = await getAuthenticatedUserId(req);
  if (!userId) return json({ error: "Sign in to ANB AI before using Online AI." }, 401);

  const apiKey = Deno.env.get("OPENAI_API_KEY");
  if (!apiKey) return json({ error: "Online AI setup incomplete: OPENAI_API_KEY secret is missing." }, 503);
  const supabaseUrl = Deno.env.get("SUPABASE_URL");
  const serviceRoleKey = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY");
  if (!supabaseUrl || !serviceRoleKey) return json({ error: "Server usage-limit configuration is missing." }, 503);

  let body: { messages?: unknown };
  try {
    const raw = await req.text();
    if (raw.length > 24000) return json({ error: "Request is too large." }, 413);
    body = JSON.parse(raw);
  } catch { return json({ error: "Invalid JSON request." }, 400); }
  if (!Array.isArray(body.messages) || body.messages.length < 1 || body.messages.length > 20) return json({ error: "messages must contain 1 to 20 items." }, 400);

  const messages: { role: "user" | "assistant"; content: string }[] = [];
  let totalChars = 0;
  for (const item of body.messages) {
    if (!item || typeof item !== "object") return json({ error: "Invalid message item." }, 400);
    const role = (item as Record<string, unknown>).role;
    const content = (item as Record<string, unknown>).content;
    if ((role !== "user" && role !== "assistant") || typeof content !== "string" || content.trim().length === 0 || content.length > 4000) return json({ error: "Each message needs a user/assistant role and 1–4000 characters of content." }, 400);
    totalChars += content.length;
    if (totalChars > 16000) return json({ error: "Conversation context is too large." }, 413);
    messages.push({ role, content: content.trim() });
  }
  if (messages[messages.length - 1]?.role !== "user") return json({ error: "The last message must be from the user." }, 400);

  try {
    const limited = await fetch(`${supabaseUrl}/rest/v1/rpc/consume_anb_ai_request`, {
      method: "POST",
      headers: { "apikey": serviceRoleKey, "Authorization": `Bearer ${serviceRoleKey}`, "Content-Type": "application/json" },
      body: JSON.stringify({ p_user_id: userId, p_daily_limit: 30 }),
    });
    if (!limited.ok) { console.error("Daily usage limit RPC failed:", limited.status); return json({ error: "Online AI usage-limit service is unavailable." }, 503); }
    const result = await limited.json();
    const usage = Array.isArray(result) ? result[0] : result;
    if (usage?.allowed !== true) return json({ error: "Daily Online AI limit reached (30 requests). Try again tomorrow." }, 429);
  } catch { return json({ error: "Online AI usage-limit service is unavailable." }, 503); }

  const model = Deno.env.get("OPENAI_MODEL");
  if (!model) return json({ error: "Server setup incomplete: set OPENAI_MODEL to a model ID available in your OpenAI API project." }, 503);

  try {
    const response = await fetch("https://api.openai.com/v1/responses", {
      method: "POST",
      headers: { "Authorization": `Bearer ${apiKey}`, "Content-Type": "application/json" },
      body: JSON.stringify({
        model,
        instructions: "You are ANB AI, a helpful personal assistant. Reply clearly in the user's language, including Hindi or Hinglish when appropriate. Do not claim you performed actions you did not perform.",
        input: messages,
        max_output_tokens: 800,
        store: false,
      }),
    });
    const result = await response.json();
    if (!response.ok) { console.error("OpenAI request failed:", response.status, result?.error?.code ?? "unknown_error"); return json({ error: "Online AI request failed. Check the API key, model and API billing." }, 502); }
    const answer = typeof result.output_text === "string" ? result.output_text.trim() : "";
    if (!answer) return json({ error: "The AI returned an empty response." }, 502);
    return json({ reply: answer, model: result.model ?? "unknown" });
  } catch (error) {
    console.error("ANB AI upstream request failed:", error instanceof Error ? error.message : "unknown");
    return json({ error: "Could not reach the online AI service." }, 502);
  }
});
