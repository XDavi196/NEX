# NEX — status real da integração Nextendo

**Atualizado em 5 de novembro de 2026**

## OAuth URL mais recente do Developer Portal

A URL gerada para o Client ID do NEX usa `https://nextendo.network/oauth/authorize`, o redirect `com.nex.nx://oauth/callback` e os escopos `identity friends presence profil game.matchmaking mods.favorites`. O valor `YOUR_CODE_CHALLENGE` é apenas um placeholder: o NEX calcula um `code_verifier` e um `code_challenge` S256 novos a cada login. A troca do código continua em `POST /api/oauth/token`, conforme a documentação técnica publicada.

Este documento separa o que já existe no código do NEX do que ainda falta para uma sessão multiplayer real. Uma conta conectada ou um token `nx2.` recebido não significam que os jogos já estejam online.

## Onde encontrar o Nextendo dentro do aplicativo

O APK `v0.1.0-alpha.48` tinha a entrada inicial **Nextendo Network** somente na lista raiz de Configurações. Por isso, ela era fácil de não perceber.

A branch de desenvolvimento agora adiciona um cartão identificado **Nextendo Network** diretamente na tela principal de jogos, logo abaixo do cabeçalho. Esse cartão abre `NextendoAuthManager.openAccount()`: sem sessão, inicia o OAuth do NEX; com sessão, abre o perfil com a ação **Amigos**. A lista de amigos consulta o `/api/oauth/userinfo` usando o access token OAuth da sessão NEX e permanece somente de leitura.

O cartão principal foi ampliado para mostrar o nome do serviço e a descrição, em vez de depender de um pequeno ícone genérico. A autenticação continua usando o Client ID público do NEX (`nxc_yAdR-14bOEq7`), o redirect `com.nex.nx://oauth/callback` e PKCE S256.

A nova logo oficial também foi adicionada a `assets/nex-logo.jpg`; o overlay a copia para `drawable-nodpi/nex_logo.jpg` e aponta os ícones `android:icon` e `android:roundIcon` para esse recurso. A mesma imagem aparece no topo do README.

**Status de distribuição:** a Release `v0.1.0-alpha.48` é anterior ao cartão da tela principal e à troca da logo no ícone Android. Essas duas mudanças estão na branch e aguardam validação na Build #51 antes de aparecerem em um APK publicado.

## O que já foi adicionado à branch de integração

| Área | Estado | Arquivos do NEX |
|---|---|---|
| OAuth Android | Implementação inicial de Authorization Code + PKCE, callback e troca de tokens | `NextendoAuthClient.kt`, `NextendoAuthManager.kt`, `NextendoCallbackActivity.kt` |
| Sessão protegida | Access/refresh token e transação PKCE cifrados com Android Keystore | `NextendoSessionStore.kt` |
| Identidade do cliente | Envia `X-Nextendo-Client-Id` com o Client ID do NEX nas chamadas REST | `NextendoAuthClient.kt`, `NextendoAccountApi.kt` |
| Perfil | Consulta de perfil do próprio jogador | `NextendoAccountApi.kt` |
| Lista de amigos no app | Consulta somente de leitura via `GET /api/oauth/userinfo`, com UI simples | `NextendoSocialApi.kt`, `NextendoAuthManager.kt` |
| Presença de aplicativo/jogo | Publica online, heartbeat a cada 60 segundos, offline no logout e status in-game com o Title ID obtido pelo playtime bridge do Eden | `NextendoPresencePublisher.kt`, `EmulationActivity.kt` via overlay |
| Token de jogo | Solicita o token separado `nx2.` em `/api/nex-token` e guarda-o separado do OAuth | `NextendoAuthClient.kt`, `NextendoSessionStore.kt` |
| Overlay Android | Copia fontes Kotlin, registra callback e adiciona a entrada Nextendo às configurações do Eden | `scripts/apply-eden-overlay.py` |

**Limites atuais do código Android:** a lista de amigos é somente leitura; envio/aceite/recusa de pedidos ainda não foi ligado. O overlay agora conecta `EmulationActivity.onEmulationStarted()` e `onEmulationStopped()` ao publisher, usando `NativeLibrary.playTimeManagerGetCurrentTitleId()` para status in-game. O Title ID é enviado como `app_id`; `app_field` continua vazio até a integração HLE produzir o payload binário real exigido por um jogo.

## Arquivos do Eden que ainda precisam ser integrados

O repositório NEX ainda não contém uma cópia modificada do código C++ do Eden. O workflow baixa a revisão fixada e aplica apenas o overlay Android. Para o multiplayer real, estes são os pontos do código-base que precisam de implementação:

| Caminho no Eden | O que falta |
|---|---|
| `src/core/hle/service/acc/acc.cpp` | Implementar o caminho BAAS/ACC que constrói e entrega o `id_token` de jogo, incluindo o token `nx2.` inteiro na claim `nnex`, PID e assinatura correta. A revisão fixada ainda tem handlers de ID-token como stubs. |
| `src/core/hle/service/friend/friend.cpp` | Alimentar as estruturas IPC de `nn::friends` com a identidade, amigos e presença vindos do cache NEX. O jogo não lê a lista da interface Android. |
| `src/core/hle/service/sockets/sfdnsres.cpp` | Trocar o bloqueio atual de domínios Nintendo por mapeamento explícito dos hostnames necessários para os endereços Nextendo. A regra deve ser por hostname/serviço, nunca wildcard geral. |
| `src/common/settings.h` e código de persistência/UI de configurações | Adicionar toggle Nextendo e configuração dos endereços do servidor principal e do segundo ponto NAT, sem hardcode de infraestrutura. |
| `src/core/hle/service/ssl/ssl.cpp` e `src/core/hle/service/ssl/ssl_backend_openssl.cpp` | Validar a rota TLS/SNI. O backend Eden já envia SNI quando o jogo define o hostname; falta testar com os hostnames redirecionados e preservar a validação normal do certificado. Não usar bypass SSL. |
| `src/core/hle/service/sockets/bsd.cpp` | Ajustar o ciclo de vida do socket UDP entre NAT-check e P2P, mantendo o mesmo mapeamento quando o protocolo do jogo exige rebind/reuso. |
| `src/core/internal_network/sockets.h` e implementação correspondente | Implementar e testar a retenção/reativação temporária do socket físico, com timeout e limpeza; não deixar sockets presos indefinidamente. |
| Android `NativeLibrary.kt`, `native.cpp` e ligação com o ciclo de vida do jogo | Passar do núcleo nativo ao `NextendoPresencePublisher.setGameActivity()` o Title ID real e o detalhe do jogo; limpar a presença in-game ao fechar o título. |
| Integração de rede por jogo | Validar autenticação NEX/PRUDP, matchmaking, NAT-check e P2P com duas contas e dois dispositivos. Alguns jogos também podem exigir ajustes específicos de protocolo ou dados de jogo próprios. |

## O que ainda falta na camada Nextendo

1. Confirmar ao vivo que o Client ID e o redirect URI configurados no Developer Portal correspondem exatamente ao build.
2. Testar OAuth e refresh-token rotation com uma conta NEX real.
3. Testar `/api/nex-token` com OAuth access token e o header de identidade do cliente. O guia atual documenta esse fluxo; um snapshot público antigo do backend tinha validações de bearer diferentes. Se houver discrepância, seguir o comportamento confirmado pela equipe Nextendo.
4. Confirmar a estrutura JWT/BAAS necessária para a revisão de Eden e a chave/configuração pertencente ao NEX. Não usar chave de outro emulador nem assinar um token fictício.
5. Depois de integrar as camadas HLE e rede, testar um título suportado de ponta a ponta.

## Portal do desenvolvedor e “Official App”

O NEX usa um cliente público próprio do Developer Portal e OAuth PKCE. **Não estamos pedindo o selo/endosso “Official App”**: a equipe Nextendo esclareceu que esse é um status separado e que a identidade do cliente comum é gerenciada pelo portal.

O código usa o cabeçalho `X-Nextendo-Client-Id` com o Client ID do NEX. Não foi adicionada chave privada inventada nem credencial de outro emulador. Recursos explicitamente reservados a apps oficiais, como cloud saves e gestão da conta, não fazem parte do escopo atual do NEX.

## Pendências de configuração que precisam ser reconciliadas

Há uma divergência entre o README da branch principal e a branch de integração:

- README de `main`: `nxc_6BozwtiXy9n`
- Código/README desta branch: `nxc_yAdR-14bOEq7`

Antes de testar login, confirme qual dos dois Client IDs é o que está realmente registrado para o NEX no Developer Portal. O build não deve tentar alternar silenciosamente entre identidades.

## Build e CI

- **Build #48:** primeira compilação completa aprovada; gerou `NEX-Android-v0.2.0-rc1-optimized.apk` e a Release experimental `v0.1.0-alpha.48`. Esse APK tem a entrada inicial do Nextendo no root das Configurações, não o cartão da tela principal nem o novo ícone.
- **Build #49:** falhou durante a aplicação do overlay porque o script não encontrou o anchor de import do `GamesFragment`.
- **Build #50:** corrigiu esse anchor, passou pela etapa `Apply NEX identity and Nextendo Android integration` e está na compilação Gradle do APK.
- **Build #51:** está na fila. Inclui o cartão grande Nextendo na tela principal, o logo em `assets/nex-logo.jpg`, a cópia para os recursos Android e a troca dos ícones do app para a nova marca.

A fila de CI mantém `cancel-in-progress: false`, então novos commits não devem interromper uma compilação nativa ativa. Nenhum APK com o cartão principal e com o novo ícone está confirmado até o build correspondente concluir o Gradle, o empacotamento e o upload.
## Critério para dizer “online funcional”

Só considerar concluído depois que duas contas NEX entrarem na mesma sessão de um jogo suportado, com BAAS/claim `nnex`, autenticação NEX, matchmaking, NAT-check e tráfego P2P funcionando. Login OAuth, lista social e obtenção de `nx2.` são etapas necessárias, mas não substituem esse teste.
