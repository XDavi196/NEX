# NEX — arquitetura de integração Nextendo e multiplayer

## Objetivo

Implementar suporte próprio do NEX à Nextendo Network no fork Android do Eden v0.2.0-rc1 Optimized, com package ID `com.nex.nx`. O NEX mantém sua própria identidade OAuth, sua própria integração Android e suas próprias credenciais emitidas/aprovadas para o aplicativo. Não importa credenciais privadas, chaves de assinatura ou material de atestação de outros emuladores.

## Separação de credenciais

As credenciais abaixo têm finalidades diferentes e não podem ser substituídas umas pelas outras.

| Credencial | Finalidade | Onde deve ser usada |
|---|---|---|
| OAuth Client ID `nxc_yAdR-14bOEq7` | Identifica o cliente público NEX no fluxo OAuth | Authorization Code + PKCE |
| OAuth access token | Chama APIs de conta dentro dos escopos autorizados | APIs de identidade, amigos, perfil e presença, conforme contrato confirmado |
| OAuth refresh token | Renova a sessão do jogador | Somente endpoint OAuth de renovação |
| Game token `nx2.` | Prova a identidade Nextendo do jogador no login NEX de cada jogo | Claim `nnex` do BAAS `id_token` |
| BAAS signing key do NEX | Assina o JWT BAAS emitido localmente pelo emulador | HLE ACC/BAAS, conforme chave pública/JWKS confiada pelo serviço |
| App identity / client-attestation credential | Demonstra ao gateway que a requisição veio de uma build NEX autorizada | Cabeçalhos e assinatura de cada chamada protegida, conforme o protocolo emitido pelo Nextendo |

Um Bot Token `nxt_bot_...` não é necessário no APK e não substitui nenhuma das credenciais acima. Client ID, access token, game token, BAAS signing key e app-attestation key não são intercambiáveis.

## 1. Lista de amigos e perfil

### Camada de conta (Android / REST)

A lista do app NEX deve ser consumida fora das threads de emulação e de rede do jogo.

Fluxo proposto:

1. Obter o PID e a identidade básica do jogador pela resposta OAuth documentada.
2. Consultar a lista de amigos com o escopo `friends`.
3. Normalizar cada registro para um modelo NEX próprio: PID, nome exibido, Friend Code, avatar, estado de presença, title ID, detalhe do jogo e favorito, quando fornecidos pelo serviço.
4. Cache de curta duração, renovação em segundo plano, cancelamento ao desconectar e limpeza dos dados pessoais no logout.
5. A camada Android agora consulta `GET /api/oauth/userinfo` com OAuth Bearer e o escopo `friends`, normaliza a lista e mostra uma tela simples de amigos. A consulta é de leitura; enviar/aceitar/recusar/remover pedidos ainda não está ligado, pois os endpoints de mutação e a autenticação de cada rota precisam ser validados no gateway em produção.

O backend público também lista rotas mais completas em `/api/friends`. NEX não as chama até confirmar o contrato OAuth atual.

### Camada HLE (serviços locais do jogo)

Os jogos Switch não consultam a tela Android de amigos. Eles chamam serviços locais do Horizon, em especial `nn::friends`. O NEX precisa projetar os dados de conta para as estruturas/IPC esperadas pelo jogo, incluindo PID/NetworkServiceAccountId, FriendCode, perfil e presença. A camada REST só alimenta o cache; as respostas IPC precisam continuar rápidas e nunca fazer HTTP síncrono na thread PRUDP/online do jogo.

## 2. Presença online

Há dois usos distintos de presença:

- **Presença social do aplicativo:** informa que o jogador está conectado ao NEX.
- **Presença do jogo:** informa o título atual, estado, lobby e, para certos títulos, o campo de aplicação/protocolo que permite entrar na sala de um amigo.

A documentação Nextendo descreve `POST /api/presence` e exige uma atualização ao menos a cada 90 segundos; sem heartbeat a presença expira. O modelo de servidor público documenta status 0 (offline), 1 (online) e 2 (online em jogo), além de `app_field`, `app_id` e `app_detail`.

Implementação Android parcial já adicionada:

1. Após login OAuth, o NEX publica status 1 (online).
2. `NextendoPresencePublisher.kt` envia heartbeat a cada 60 segundos, sem bloquear a thread de emulação.
3. Ao desconectar a conta, tenta publicar status 0 (offline).
4. `setGameActivity()` está disponível, mas ainda não recebe o Title ID real do núcleo nativo. Portanto o NEX ainda não publica status 2 (in-game) de forma integrada.
5. `app_field` permanece vazio até o HLE produzir o campo binário real de um título; NEX não inventa esse valor.
6. O servidor deve expirar a presença após crash/força-parada.

A documentação do Developer Portal diz OAuth Bearer em `/api/presence`. Um snapshot público antigo do backend mostrava validação diferente para essa rota. O código segue a documentação atual, mas o heartbeat precisa de teste real antes de ser considerado funcional.

## 3. BAAS e claim nnex

O fluxo de login do jogo é independente do login OAuth:

1. NEX recebe uma sessão OAuth autorizada.
2. NEX obtém um token de jogo assinado `nx2.` pela rota oficial Nextendo.
3. O HLE ACC/BAAS constrói o `id_token` JWT solicitado pelo jogo e inclui o token `nx2.` inteiro na claim `nnex`.
4. O `id_token` é assinado com a chave BAAS específica do NEX, usando algoritmo, `kid`, `iss`, `aud`, `jku`, expiração e demais claims exatamente aprovados pelo Nextendo e pelo título.
5. O jogo encaminha o token ao serviço de autenticação NEX; o servidor valida a assinatura BAAS e a claim `nnex`, incluindo PID e validade.

Regras obrigatórias:

- Nunca inserir o OAuth access token na claim `nnex`.
- Nunca usar um PID isolado, token vazio, ou token `nx2` inventado.
- Não gerar uma chave aleatória de BAAS como solução de produção.
- Não reutilizar o certificado, `kid`, `jku`, audience, issuer ou chave privada de outra build.
- Se o jogo validar a assinatura localmente por JWKS, a chave pública publicada pelo endpoint/JWKS deve corresponder à chave privada NEX usada para assinar o JWT.

## 4. DNS, TLS e SNI

O jogo continua a resolver os hostnames originais do serviço online. O emulador intercepta apenas os hostnames Nextendo oficialmente mapeados e resolve-os para os endereços Nextendo configurados.

Camadas previstas:

- Resolver DNS MITM dentro do subsistema SFDNSRES, com precedência documentada para entradas explícitas de hosts.
- Tabela de hostnames por jogo/serviço, mantida por configuração e compatibilidade publicada, não por wildcard global.
- Dois endereços distintos para os caminhos que exigem NAT check: servidor principal e segundo ponto de observação.
- Preservar TLS normal: validação de certificado, trust store do emulador e SNI coerente com o hostname solicitado.
- Quando um título abre TLS por IP sem SNI, recuperar o hostname original da resolução DNS e corrigir o ClientHello de forma limitada, sem desativar verificação de certificado.
- Não implementar SSL bypass, não aceitar certificados arbitrários e não interceptar tráfego que não pertença aos hostnames explicitamente autorizados.

O roteamento TLS do lado Nextendo usa SNI para direcionar o tráfego de autenticação para o servidor de jogo adequado.

## 5. NEX / PRUDP, NAT e P2P

Matchmaking e NAT traversal são fases diferentes:

1. O jogo autentica via TLS/SNI e recebe ticket de sessão.
2. Conecta ao servidor seguro do jogo (NEX/PRUDP).
3. Matchmaking localiza os participantes e negocia os endpoints.
4. O jogo executa NAT-check contra os dois pontos de observação Nextendo.
5. Depois do resultado, os clientes tentam UDP hole-punching e enviam o tráfego da partida diretamente entre peers, quando o título usa P2P.

O HLE BSD precisa preservar corretamente o socket UDP e o mapeamento NAT durante a transição entre NAT-check e sessão P2P. Não se deve fechar/reabrir o socket indiscriminadamente se o jogo espera reutilizar o mesmo mapeamento. A correção precisa ser específica para o padrão de bind/rebind observado e não pode manter sockets indefinidamente.

Também precisamos validar:
- portas locais e remotas, socket reuse, timeout e limpeza no fim da emulação;
- NAT simétrico, cone/restricted NAT, Wi-Fi e redes móveis;
- IPv4/IPv6 conforme suporte do servidor;
- corrida entre peers e retransmissão PRUDP;
- fallback/erro quando NAT ou UDP é bloqueado.

## 6. Identidade do cliente pelo Developer Portal

NEX usa um **cliente público próprio registrado no Developer Portal**, com OAuth Authorization Code + PKCE. Essa identidade de cliente é gerenciada pelo portal; não estamos solicitando o selo ou endosso separado de **“Official App”**.

As chamadas REST enviam o cabeçalho:

`X-Nextendo-Client-Id: <NEX Client ID>`

O código usa o Client ID configurado nesta branch. Não há Client Secret nem Bot Token embutido no APK, e nenhuma chave/credencial de outro emulador é reutilizada.

A equipe Nextendo esclareceu que “Official App” é um status separado e que o cliente deve usar o que o Developer Portal já fornece. Portanto, NEX não deve inventar uma chave privada de atestação, nem pedir credenciais que o portal não disponibiliza. A changelog v1.8.6/v1.8.7 descreve identidade de aplicação e attestation para clientes de emulador, mas a integração NEX deve seguir as instruções atuais do Developer Portal e validar as respostas reais do gateway.

Recursos que a documentação reserva a aplicações oficiais, como cloud saves e gestão da conta, ficam fora do escopo atual do NEX.

## 7. Inconsistências de contrato a confirmar

A documentação pública atual descreve OAuth access token para APIs de conta e `/api/nex-token`. O código público do serviço de contas que foi consultado mostra alguns handlers (`/api/profile`, `/api/friends`, `/api/presence`, `/api/nex-token`) usando `accountFromBearer`, que aceita token de sessão web ou `nx2` e não o tipo de OAuth token. O OAuth `userinfo` valida o access token explicitamente. A implementação publicada de `/api/oauth/token` também precisa ser verificada para o fluxo de `refresh_token`.

Antes do lançamento, solicitar à equipe Nextendo exemplos reais (sem dados de usuários) de:
- perfil GET e update de perfil (escopo `profil`);
- amigos GET, incoming requests e ações de aceitar/recusar;
- payload de presença e comportamento de logout/offline;
- `/api/nex-token` com OAuth access token;
- refresh-token rotation;
- headers/assinatura de client attestation;
- JWT BAAS, JWKS e parâmetros NEX.

## 8. Plano de testes e critérios de aceite

**Etapa A — conta**
- OAuth PKCE Android com callback exato.
- refresh-token rotation e logout/revogação.
- userinfo identity e friend code.
- perfil e amigos com consentimento.

**Etapa B — social**
- lista de amigos, pedidos, cache e avatar.
- presença online/in-game/offline, heartbeat e expiração.
- teste de duas contas NEX em dois dispositivos.

**Etapa C — autenticação de jogo**
- token `nx2` válido e expirado.
- BAAS JWT assinado com chave NEX provisionada.
- claim `nnex` validada pelo jogo/servidor.
- recusa controlada para app não provisionado ou conta sem autorização.

**Etapa D — rede**
- DNS mapping por hostname/título.
- TLS com SNI e certificados validados.
- NEX auth / PRUDP / secure connection.
- matchmaking entre duas contas.
- NAT-check de ambos os lados, hole-punch e tráfego P2P real.

**Critério de release:** só declarar “multiplayer funcional” depois de uma sessão multiplayer real com duas contas NEX, jogo e versão suportados pelo Nextendo, sem erro de autenticação, matchmaking, NAT ou P2P. OAuth login ou obtenção de `nx2` não é evidência suficiente.

## Referências oficiais consultadas

- https://wiki.nextendo.network/developers
- https://wiki.nextendo.network/changelog
- https://github.com/NextendoNetwork/nextendo-docs/blob/main/ARCHITECTURE.md
- https://github.com/NextendoNetwork/nextendo-docs/blob/main/DEPLOYMENT.md
- https://github.com/NextendoNetwork/nextendo-account
- https://github.com/NextendoNetwork/Ryujinx-Nextendo
