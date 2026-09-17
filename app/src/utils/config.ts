import { Platform } from 'react-native';

const LOCAL_BACKEND = Platform.OS === 'android' ? 'http://10.0.2.2:8787' : 'http://localhost:8787';
const PRODUCTION_BACKEND = 'https://trimm.fly.dev'; // Based on fly.toml app name

export const BACKEND_URL = __DEV__ ? LOCAL_BACKEND : PRODUCTION_BACKEND;
