import plannerCore from '../server/planner-core.cjs';
import transcriptionCore from '../server/transcription-core.cjs';

export const config = { api: { bodyParser: false } };

export default async function mobileTranscribe(request, response) {
  if (request.method !== 'POST') return response.status(405).json({ error: 'Method not allowed' });
  if (!plannerCore.authorized(request.headers.authorization)) return response.status(401).json({ error: 'Unauthorized' });
  try {
    transcriptionCore.validateUpload(request.headers);
    return response.status(200).json(await transcriptionCore.transcribe(request));
  } catch (error) {
    console.error('[transcribe] request failed', { message: error?.message, status: error?.status });
    return response.status(Number.isInteger(error?.status) ? error.status : 502).json({ error: error?.message || 'Transcription failed' });
  }
}
