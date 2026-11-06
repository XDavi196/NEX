# NEX — status real da integração Nextendo

**Atualizado em 5 de novembro de 2026**

## OAuth URL mais recente do Developer Portal

A URL gerada para o Client ID do NEX usa `https://nextendo.network/oauth/authorize`, o redirect `com.nex.nx://oauth/callback` e os escopos `identity friends presence profil game.matchmaking mods.favorites`. O valor `YOUR_CODE_CHALLENGE` é apenas um placeholder: o NEX calcula um `code_verifier` e um `code_challenge` S256 novos a cada login. A troca do código continua em `POST /api/oauth/token`, conforme a documentação técnica publicada.

Este documento separa o que já existe no código do NEX do que ainda falta para uma sessão multiplayer real. Uma conta conectada ou um token `nx2.` recebido não significam que os jogos já estejam online.

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

- **Build #42:** falhou antes do Gradle porque o overlay não identificava a importação `NativeLibrary` no arquivo `EmulationActivity.kt`. Esse anchor foi corrigido.
- **Builds #43, #44 e #45:** canceladas pela configuração antiga `cancel-in-progress: true`, que interrompia uma build ativa sempre que outro commit era enviado.
- **Build #46:** passou pelo checkout, instalação da toolchain e aplicação do overlay. Falhou na compilação Kotlin, em `EmulationActivity.kt:44`. O overlay havia inserido a importação `NextendoPresencePublisher` com os caracteres literais `\\n` em vez de uma quebra de linha real. Isso causou erros de sintaxe e os erros subsequentes de referência a `R`.
- **Correção enviada:** `scripts/apply-eden-overlay.py` agora monta essa linha de importação com uma quebra de linha real, igual ao padrão já usado nas outras inserções. A alteração está no commit `a29dadb5b75edea6be04946fdaf5a674ffbc2e67`.
- **Build #47:** falhou na mesma tarefa Kotlin que a #46 (`EmulationActivity.kt:44`). Ela usou o commit anterior à correção, com o `\\n` literal, portanto repetiu o mesmo erro; não revelou um novo problema.
- **Build #48:** está usando o commit corrigido, passou pelo overlay e está em `Build NEX Android Optimized` (Gradle). A compilação Kotlin ainda não foi confirmada como concluída.

A concorrência do workflow foi alterada para `cancel-in-progress: false`, e README/alterações somente documentais foram removidas do filtro de gatilhos de build. Assim, uma nova alteração não deve interromper a compilação ativa, e modificações apenas no README não devem consumir outra build nativa.

Ainda não há APK aprovado nem GitHub Release. A pré-release só será publicada quando uma execução com assunto iniciado por `[release]` concluir o Gradle, empacotar o APK e enviar o artefato com o checksum com sucesso.
## Critério para dizer “online funcional”

Só considerar concluído depois que duas contas NEX entrarem na mesma sessão de um jogo suportado, com BAAS/claim `nnex`, autenticação NEX, matchmaking, NAT-check e tráfego P2P funcionando. Login OAuth, lista social e obtenção de `nx2.` são etapas necessárias, mas não substituem esse teste.
