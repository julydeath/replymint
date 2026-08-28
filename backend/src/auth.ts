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

function webClientId(): string {
  const id = process.env.GOOGLE_WEB_CLIENT_ID;
  if (!id) throw new Error("GOOGLE_WEB_CLIENT_ID is not set");
  return id;
}

const sha256 = (s: string) => createHash("sha256").update(s).digest("hex");

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
    googleClient ??= new OAuth2Client(webClientId());
    const ticket = await googleClient.verifyIdToken({ idToken, audience: webClientId() });
    const payload = ticket.getPayload();
    if (!payload?.sub) throw new Error("no subject");
    ({ sub, email, name } = payload);
  } catch {
    return c.json({ error: "invalid google token" }, 401);
  }

  const user = await upsertUserByGoogle(sub, email ?? "", name ?? null);
  const token = "rt_" + randomBytes(32).toString("base64url");
  await insertToken(sha256(token), user.id);
  return c.json({ token, user: { email: user.email, name: user.name } });
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
