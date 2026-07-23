import transcriptionCore from '../server/transcription-core.cjs';
import authenticationModule from '../server/authentication.cjs';
import requestPolicy from '../server/request-policy.cjs';

const authentication = authenticationModule.fromEnvironment();

export const config = { api: { bodyParser: false } };

export default async function mobileTranscribe(request, response) {
  if (request.method !== 'POST') return response.status(405).json({ error: 'Method not allowed' });
  try { request.principal = (await requestPolicy.authorizeRequest(request, authentication, { scope: 'transcribe:create', action: 'create', resourceType: 'transcription' })).principal; }
  catch (error) { return requestPolicy.authorizationResponse(response, error); }
  try {
    transcriptionCore.validateUpload(request.headers);
    return response.status(200).json(await transcriptionCore.transcribe(request));
  } catch (error) {
    console.error('[transcribe] request failed', { message: error?.message, status: error?.status });
    return response.status(Number.isInteger(error?.status) ? error.status : 502).json({ error: error?.message || 'Transcription failed' });
  }
}
