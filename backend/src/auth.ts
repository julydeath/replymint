import { createHash, randomBytes } from "node:crypto";
import type { Context, MiddlewareHandler } from "hono";
import { OAuth2Client } from "google-auth-library";
import { insertToken, deleteToken, upsertUserByGoogle, userForTokenHash, type User } from "./db.js";

/**
 * Auth: the Google ID token is verified exactly once, at sign-in, then exchanged for an opaque
 * long-lived token (`rt_` + 32 random bytes). Only the token's sha256 is stored, so a DB leak
 * doesn't leak usable credentials. /v1/reply fires from the bubble with no UI available for a
 * silent Google-token refresh, which is why we don't verify Google ID tokens per request.
 */

export type AuthEnv = { Variables: { user: User; tokenHash: string } };

let googleClient: OAuth2Client | undefined;

/**
 * Every OAuth client id whose ID tokens we accept (web/Android sign-in plus the desktop
 * client). GOOGLE_CLIENT_IDS is comma-separated; GOOGLE_WEB_CLIENT_ID alone still works.
 */
function clientIds(): string[] {
  const ids = (process.env.GOOGLE_CLIENT_IDS ?? process.env.GOOGLE_WEB_CLIENT_ID ?? "")
    .split(",")
    .map((s) => s.trim())
    .filter(Boolean);
  if (ids.length === 0) throw new Error("GOOGLE_CLIENT_IDS / GOOGLE_WEB_CLIENT_ID is not set");
  return ids;
}

const sha256 = (s: string) => createHash("sha256").update(s).digest("hex");

/** Upserts the Google identity and mints an opaque rt_ token — shared tail of both sign-in routes. */
async function mintToken(
  sub: string,
  email: string | undefined,
  name: string | undefined,
  platform?: string,
  deviceName?: string
) {
  const user = await upsertUserByGoogle(sub, email ?? "", name ?? null);
  const token = "rt_" + randomBytes(32).toString("base64url");
  await insertToken(sha256(token), user.id, platform, deviceName);
  return { token, user: { email: user.email, name: user.name } };
}

/** POST /v1/auth/google — body { idToken } → { token, user: { email, name } } */
export async function exchangeGoogleToken(c: Context) {
  let idToken: unknown;
  try {
    ({ idToken } = await c.req.json());
  } catch {
    return c.json({ error: "invalid json" }, 400);
  }
  if (typeof idToken !== "string" || !idToken) {
    return c.json({ error: "idToken required" }, 400);
  }

  let sub: string, email: string | undefined, name: string | undefined;
  try {
    googleClient ??= new OAuth2Client();
    const ticket = await googleClient.verifyIdToken({ idToken, audience: clientIds() });
    const payload = ticket.getPayload();
    if (!payload?.sub) throw new Error("no subject");
    ({ sub, email, name } = payload);
  } catch {
    return c.json({ error: "invalid google token" }, 401);
  }

  return c.json(await mintToken(sub, email, name, "android"));
}

/**
 * POST /v1/auth/google/desktop — loopback-OAuth code exchange for the Mac/PC apps.
 * Body { code, codeVerifier, redirectUri, platform?, deviceName? } → { token, user }.
 *
 * Desktop-app Google clients require the client secret at code exchange; doing the
 * exchange here keeps that secret out of the shipped binary. PKCE (codeVerifier) is
 * what actually protects the flow — the "secret" of a desktop client is not secret.
 */
export async function exchangeGoogleCode(c: Context) {
  let body: Record<string, unknown>;
  try {
    body = await c.req.json();
  } catch {
    return c.json({ error: "invalid json" }, 400);
  }
  const { code, codeVerifier, redirectUri, platform, deviceName } = body;
  if (typeof code !== "string" || !code) return c.json({ error: "code required" }, 400);
  if (typeof codeVerifier !== "string" || !codeVerifier) {
    return c.json({ error: "codeVerifier required" }, 400);
  }
  if (
    typeof redirectUri !== "string" ||
    !(redirectUri.startsWith("http://127.0.0.1:") || redirectUri.startsWith("http://localhost:"))
  ) {
    return c.json({ error: "redirectUri must be a loopback address" }, 400);
  }

  const desktopId = process.env.GOOGLE_DESKTOP_CLIENT_ID;
  const desktopSecret = process.env.GOOGLE_DESKTOP_CLIENT_SECRET;
  if (!desktopId || !desktopSecret) {
    return c.json({ error: "desktop sign-in not configured" }, 501);
  }

  let sub: string, email: string | undefined, name: string | undefined;
  try {
    const exchanger = new OAuth2Client(desktopId, desktopSecret, redirectUri);
    const { tokens } = await exchanger.getToken({ code, codeVerifier });
    if (!tokens.id_token) throw new Error("no id_token in exchange response");
    const ticket = await exchanger.verifyIdToken({
      idToken: tokens.id_token,
      audience: clientIds(),
    });
    const payload = ticket.getPayload();
    if (!payload?.sub) throw new Error("no subject");
    ({ sub, email, name } = payload);
  } catch (e) {
    console.error("desktop code exchange failed:", (e as Error).message);
    return c.json({ error: "invalid google token" }, 401);
  }

  return c.json(
    await mintToken(
      sub,
      email,
      name,
      typeof platform === "string" ? platform : "desktop",
      typeof deviceName === "string" ? deviceName : undefined
    )
  );
}

/** POST /v1/auth/signout — revokes the presented token. Always 200 (idempotent). */
export async function signOut(c: Context) {
  const token = bearerToken(c);
  if (token) await deleteToken(sha256(token));
  return c.json({ ok: true });
}

function bearerToken(c: Context): string | null {
  const auth = c.req.header("authorization") ?? "";
  return auth.startsWith("Bearer ") ? auth.slice("Bearer ".length) : null;
}

/** Resolves the bearer token to a user or 401s. Sets c.var.user. */
export const requireAuth: MiddlewareHandler<AuthEnv> = async (c, next) => {
  const token = bearerToken(c);
  if (!token || !token.startsWith("rt_")) {
    return c.json({ error: "unauthorized" }, 401);
  }
  const hash = sha256(token);
  const user = await userForTokenHash(hash);
  if (!user) return c.json({ error: "unauthorized" }, 401);
  c.set("user", user);
  c.set("tokenHash", hash);
  await next();
};
