# Morera IPTV

A [plataforma React e Spring Boot](platform/README.md) está em `platform/`. Este guia descreve a versão original em Node.js, mantida para a migração.

Painel local para importar listas IPTV, navegar por categorias e reproduzir conteúdo compatível com o navegador.

## Executar no Windows

Com Node.js 22 ou superior, abra o PowerShell nesta pasta e execute:

```powershell
npm ci
npm start
```

Abra [Morera IPTV](http://127.0.0.1:4173) e clique em **Adicionar lista**.

- **Servidor Xtream Codes** aceita o endereço base, incluindo porta quando exigida, usuário e senha.
- **Link M3U / M3U8** aceita o endereço completo fornecido pelo serviço.
- **Arquivo de lista M3U** importa um catálogo com endereços HTTP ou HTTPS.

O painel roda no próprio computador. O servidor mantém os endereços da lista em memória. A biblioteca e os favoritos ficam disponíveis durante a sessão da página. A reprodução depende da disponibilidade da transmissão e dos formatos e codecs aceitos pelo navegador.


## DNS da conexão

Em **Adicionar lista**, o Nexo IPTV oferece **Cloudflare (DNS sobre HTTPS)** como padrão. O servidor usa essa opção para resolver os domínios da lista e da mídia, inclusive os de redirecionamentos e segmentos HLS. Para domínios internos ou quando a resolução pela Cloudflare falhar, escolha **DNS do sistema** e carregue a lista novamente. A configuração vale para o Nexo IPTV e não altera o DNS do Windows.

