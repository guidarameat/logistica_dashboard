# Deploy na AWS (S3 + CloudFront + App Runner), pelo console

Ordem importante: o backend precisa da URL do frontend (CORS) e o frontend
precisa da URL do backend (build). Por isso: 1) backend, 2) frontend, 3) ajustar CORS.

## 0. Antes de começar
- Conta AWS com permissão para App Runner, S3 e CloudFront. Escolha uma região (ex.: `sa-east-1`).
- Faça commit e push da reorganização (`backend/`, `frontend/`) para o GitHub (branch `main`).

## 1. Backend no App Runner
1. Console AWS > **App Runner** > *Create service*.
2. Source: **Source code repository**. Conecte o GitHub e escolha `guidarameat/logistica_dashboard`, branch `main`.
3. **Source directory: `backend`** (o repositório é dividido em duas pastas).
4. Deployment: Automatic. Configuration: **Use a configuration file** (`apprunner.yaml`).
5. Service name: `guidara-backend`. CPU/memória: 1 vCPU / 2 GB já serve para começar.
6. Environment variables:
   - `FRONTEND_URL` = `https://placeholder.exemplo` (você troca no passo 3)
   - `OPENAI_API_KEY` = sua chave (tipo **Secret**, via Secrets Manager; opcional se só importar Excel)
7. Health check: protocolo HTTP, path `/api/importacoes`.
8. Crie. Ao terminar, anote a URL: `https://xxxx.sa-east-1.awsapprunner.com`.
9. Teste: abra `https://xxxx.../api/importacoes`. Deve responder `[]`.

## 2. Frontend no S3 + CloudFront
1. Na sua máquina:
   ```bash
   cd frontend
   echo "VITE_API_URL=https://xxxx.sa-east-1.awsapprunner.com" > .env.production
   npm install
   npm run build        # gera frontend/dist
   ```
2. Console > **S3** > criar bucket (ex.: `guidara-dashboard-frontend`), **bloqueando** todo acesso público.
3. Envie o **conteúdo** de `frontend/dist/` (não a pasta) para a raiz do bucket.
4. Console > **CloudFront** > *Create distribution*:
   - Origin: o bucket S3, com **Origin access control (OAC)** (deixe o console criar e copiar a policy no bucket).
   - Default root object: `index.html`.
   - Viewer protocol policy: Redirect HTTP to HTTPS.
5. Ao terminar, anote o domínio: `https://dxxxx.cloudfront.net`.

## 3. Fechar o CORS
No App Runner > serviço `guidara-backend` > Configuration > Environment variables:
`FRONTEND_URL` = `https://dxxxx.cloudfront.net` (sem barra final). Salve e aguarde o redeploy.
Abra a URL do CloudFront e importe uma planilha para testar.

## Atualizações futuras
- Backend: push na `main` redeploya sozinho (modo automático).
- Frontend: `npm run build`, reenvie `dist/` ao S3 e crie uma *invalidation* `/*` no CloudFront.

## Pontos de atenção
- **Dados em memória.** O repositório usa `ConcurrentHashMap`. Reiniciar ou fazer redeploy do backend apaga todas as importações. Para uso real, falta um banco (ex.: RDS).
- **API sem autenticação.** Qualquer pessoa com a URL do backend consegue listar dados e importar arquivos. O CORS só restringe navegadores. Se o dashboard é interno, adicione login (ex.: Cognito) antes de expor.
- **Custo.** App Runner cobra mesmo parado (memória provisionada). Pause o serviço quando não usar.
- **Build do App Runner.** Se o build falhar por falta do `mvn` no runtime `corretto17`, a alternativa é empacotar em Docker e usar o ECR (requer AWS CLI e Docker).
- **Domínio próprio** (opcional): Route 53 + certificado no ACM (para o CloudFront, em `us-east-1`).
