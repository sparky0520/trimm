import dotenv from 'dotenv';
dotenv.config({ path: new URL('../.env.local', import.meta.url).pathname });
dotenv.config({ path: new URL('../.env', import.meta.url).pathname });
