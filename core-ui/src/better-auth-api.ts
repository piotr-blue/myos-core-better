/** Existing Better Cognito flow, deliberately independent of account selection. */
export async function auth(action: string, input: unknown) {
  const response = await fetch(`/api/auth/${action}`, {
    method: 'POST', credentials: 'same-origin',
    headers: {'Content-Type': 'application/json'}, body: JSON.stringify(input),
  });
  const body = await response.json();
  if (!response.ok) throw Object.assign(new Error(body.message || body.detail || 'Unable to sign in.'), {status: response.status, code: body.code});
  return body;
}
