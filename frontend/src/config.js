// URL base da API, definida em build/dev por VITE_API_URL (ver .env.example).
// Sem barra final. Se não definida, usa a mesma origem (útil com reverse proxy em produção).
const base = (import.meta.env.VITE_API_URL ?? '').trim().replace(/\/+$/, '');

export const apiUrl = path => `${base}${path}`;
