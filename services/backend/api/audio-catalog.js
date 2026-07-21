import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

export default function audioCatalog(_request, response) {
  const file = path.join(path.dirname(fileURLToPath(import.meta.url)), '..', 'public', 'audio', 'catalog.v1.json');
  const catalog = JSON.parse(fs.readFileSync(file, 'utf8'));
  response.setHeader('Cache-Control', 'public, max-age=300, s-maxage=300, stale-while-revalidate=86400');
  return response.status(200).json(catalog);
}
