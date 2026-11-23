# Nexo IPTV em React e Spring Boot

A plataforma está nesta pasta. A versão Node.js original continua executável a partir da pasta acima e usa a porta 4173. A plataforma usa a porta 8080. As listas e favoritos do servidor original existiam em memória. Importe suas listas originais na nova plataforma depois de criar as contas.

## Iniciar

Use Docker Compose com contêineres Linux. Copie `.env.example` para `.env` e preencha `DATABASE_PASSWORD`, `ADMIN_EMAIL`, `ADMIN_PASSWORD` (mínimo de 12 caracteres) e `JWT_SECRET`. Gere o segredo no PowerShell com `[Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(32))`.

```powershell
docker compose up -d --build
```

Abra o painel em `http://127.0.0.1:8080`. A conta administrativa é criada no primeiro início bem-sucedido. Depois, retire `ADMIN_PASSWORD` do arquivo de configuração. Guarde o segredo JWT de forma segura. Ele também deriva a chave que protege as URLs salvas. Alterá-lo sem migrar os valores cifrados invalida sessões e impede a leitura dessas fontes.

No desenvolvimento com PostgreSQL e Redis existentes, instale Java 25, Maven e Node.js 22 ou superior. Exporte as variáveis do exemplo e os endereços `DATABASE_URL` e `REDIS_URL` conforme o ambiente. Rode `mvn spring-boot:run` em `backend` e `npm ci` seguido de `npm run dev` em `frontend`. O Vite encaminha a API e a mídia para a porta 8080. Use `PUBLIC_ORIGIN=http://127.0.0.1:5173` no desenvolvimento. Para publicar, configure um proxy HTTPS, `COOKIE_SECURE=true`, a origem pública HTTPS e backups do PostgreSQL. Configure SMTP para entregar os links de recuperação, que expiram em 20 minutos.

## Conectividade

Em **Configurações**, selecione um DNS autorizado e rode o diagnóstico. O DNS preferido aplica-se a novas listas. Uma lista salva mantém seu DNS de importação enquanto permitido pela política. O modo Cloudflare usa DNS sobre HTTPS, com 1.1.1.1 como primário e 1.0.0.1 como secundário. O modo automático usa o resolvedor do processo.

Em **Administração → Rede**, configure os DNS permitidos, o padrão e a rota esperada do host. Um processo Spring Boot segue uma rota de saída. Escolher uma rota no painel informa ao diagnóstico o que deve estar ativo; a configuração do túnel ocorre no sistema operacional do host que roda o Java, inclusive quando ele está em um contêiner.

- **Direta.** Use a rota normal do host, com os túneis desligados.
- **Cloudflare WARP.** Instale o cliente oficial no host e ative o modo que transporta tráfego. Em Linux, a Cloudflare documenta `warp-cli registration new`, `warp-cli connect` e a verificação por `cdn-cgi/trace`. Configure a rota do contêiner, se aplicável, e confirme pelo painel. [Cliente oficial](https://developers.cloudflare.com/warp-client/get-started/)
- **WireGuard.** Provisione o servidor e o perfil no cliente WireGuard do host. Salve no painel o endpoint público e o IP de saída esperado. O diagnóstico compara o IP observado; a confirmação de handshake é feita no host com `wg show`. [Guia WireGuard](https://www.wireguard.com/quickstart/)

Ative um túnel por vez. Quando a rota WARP ou WireGuard está configurada, o servidor exige que a verificação de saída passe antes de abrir fontes. O resultado positivo é reaproveitado por até 15 segundos. Se a rota precisar permanecer protegida quando o túnel cair, configure as regras de saída no firewall do host. O teste amostra a saída para a Cloudflare e pode divergir da rota IPTV quando há divisão de tráfego. Chaves de VPN permanecem no sistema operacional.

O teste da fonte mede DNS e resposta HTTP. O teste da mídia selecionada verifica se a fonte devolve dados. A confirmação de codecs e reprodução é feita pelo player. O player repete falhas transitórias com espera crescente. Com a rota Direta, fontes públicas HTTPS explicitamente cadastradas para reprodução direta usam a rede e o DNS do navegador e precisam permitir acesso ao manifesto e aos segmentos. Fontes privadas passam pelo servidor para proteger os endereços e lidar com restrições do navegador, gravando apenas os metadados necessários e sem transcodificar o vídeo.

FFmpeg e MediaMTX podem ser instalados como serviços externos se houver necessidade de transcodificação ou redistribuição; o player HLS e o diagnóstico atual não precisam deles. A atualização do guia XMLTV é feita manualmente na área EPG.


