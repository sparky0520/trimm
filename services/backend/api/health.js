export default function health(_request, response) {
  return response.status(200).json({ ok: true, model: process.env.MOONSHOT_MODEL || 'kimi-k3' });
}
