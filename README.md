# Projeto_Guidara

Dashboard executivo de expedição, separado em duas aplicações independentes:

| Pasta | Stack | Porta (dev) |
|---|---|---|
| `backend/`  | Spring Boot 3.3 / Java 17 / Maven | 8090 |
| `frontend/` | Vite + JavaScript (sem framework) | 5173 |

## Rodando localmente

**Backend** (Java 17+ e Maven):
```bash
cd backend
cp .env.example .env        # preencha OPENAI_API_KEY se for importar imagens
mvn spring-boot:run
```

**Frontend** (Node 18+):
```bash
cd frontend
cp .env.example .env        # VITE_API_URL=http://localhost:8090
npm install
npm run dev
```
Abra http://localhost:5173.

## Variáveis de ambiente

- `backend/.env.example`: `OPENAI_API_KEY`, `FRONTEND_URL` (CORS), `SERVER_PORT`.
- `frontend/.env.example`: `VITE_API_URL` (URL base da API; embutida no build).

## Build / produção

```bash
cd backend  && mvn clean package          # gera target/*.jar; rode com java -jar
cd frontend && npm run build              # gera dist/ (arquivos estáticos)
```
Em produção, defina `FRONTEND_URL` no backend com o domínio real do frontend e
`VITE_API_URL` **antes** do `npm run build` com o domínio real da API.
