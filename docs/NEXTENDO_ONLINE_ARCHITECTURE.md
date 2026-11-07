# Arquitetura de integração online do NEX

Atualizado em 7 de novembro de 2026.

Este documento separa o que já existe no cliente Android do que ainda depende de contrato, credenciais ou infraestrutura fornecidos pela Nextendo.

## 1. Identidade NEX

- Repositório: XDavi196/NEX.
- Branch: nex/eden-v0.2.0-rc1-nextendo.
- Base: Eden v0.2.0-rc1, commit 8e373eb714aa01117ec885680477501cdb572112.
- Android application ID: com.nex.nx.
- OAuth callback: com.nex.nx://oauth/callback.
- Client ID público NEX: nxc_yAdR-14bOEq7.

O login usa Authorization Code + PKCE (S256). O APK não contém client secret. O access token OAuth, usado nas rotas de conta documentadas, é diferente do token de jogo nx2. O token nx2. é tratado como opaco; não é um JWT BAAS e não é usado como substituto do OAuth access token.

Escopos solicitados conforme o portal do proprietário: identity, friends, presence, profil, game.matchmaking e emulator.status. Escopos não usados foram removidos.

## 2. Contrato de conta e presença

A documentação de desenvolvedor da Nextendo descreve OAuth com os escopos de presença e matchmaking, emissão de token de jogo e publicação de presença com OAuth. O código público de nextendo-account ainda apresenta diferenças: o allow-list de escopos contém identity/friends, o endpoint /api/nex-token verifica token de sessão web, e /api/presence aceita token de sessão web ou nx2.

O NEX segue o contrato documentado e não tenta contornar a divergência com login privado. A publicação de presença agora exige access token OAuth válido e escopo presence. Se a implantação pública ainda seguir o código-fonte aberto, a requisição pode ser recusada. Não há fallback para nx2.

A presença do HOME Menu não inventa AppField. O campo fica vazio até que exista um valor binário real e validado para publicar.

## 3. BAAS e ACC

O NEX mantém o UUID local do perfil Eden para operações offline, mas quando há uma conta Nextendo vinculada o ACC do jogo apresenta o PID persistente Nextendo como NetworkServiceAccountId, seguindo o comportamento do emulador de referência. Isso não converte o PID em UUID Switch e não substitui a autenticação BAAS do jogo. A emissão de id_token assinado com o token nx2. dentro da claim nnex depende de:

- contrato exato da claim, issuer, audience, kid, jku, algoritmo e validade;
- chave privada emitida especificamente para o NEX;
- chave pública NEX registrada no JWKS oficial;
- procedimento de client attestation e assinatura das chamadas exigido pela Nextendo.

A documentação pública manda seguir a implementação do emulador oficial, mas não descreve por completo esses campos. Nenhum valor é adivinhado. Sem a chave e o contrato NEX, o ACC não entrega um BAAS id_token de jogo válido.

## 4. Cache social e nn::friends

O Android mantém um snapshot limitado de amigos e transfere os campos validados para um cache nativo protegido por mutex. Os campos conhecidos são PID, username, presença, application ID, AppField e, quando a API realmente o fornece, NSA.

O serviço nn::friends usa a identidade de conta definida pela Nextendo: o emulador de referência associa o PID persistente ao NetworkServiceAccountId. O NEX agora aplica o mesmo mapeamento quando a API omite o campo NSA e preserva um NSA explícito válido quando presente. O PID não é transformado em UUID Switch; campos ausentes como UUID e último acesso permanecem zerados.

A API pública de amigos pode retornar PID e presença sem NSA nem UUID. Com o mapeamento de conta usado pelo emulador de referência, o NEX associa PID a NSA para que o jogo consiga resolver os amigos, sem inventar UUID nem campos de presença. O aplicativo ainda depende dos dados sociais efetivamente fornecidos pela API.

Os comandos modernos de qLaunch foram registrados no overlay e os serializers V2 têm testes nativos de host. Os buffers e comandos ainda precisam de validação dentro do qLaunch real. Os testes nativos do cache não substituem testes IPC visíveis ao jogo.

## 5. PRUDP-Lite, RMC e Super Mario Maker 2

Foi implementado um codec NEX-owned para o envelope PRUDP-Lite e um codec para o envelope RMC:

- PRUDP-Lite: cabeçalho, opções de handshake, tamanho de payload, concatenação de quadros e leituras TCP/WebSocket parciais.
- RMC: prefixo de tamanho, protocolo, direção, call ID, method ID e respostas de sucesso/erro.
- DataStore_SMM2: protocolo 115, com IDs de métodos transcritos do schema público.

Esses codecs não implementam o handshake completo, assinatura de pacotes, chave de acesso do jogo, janela de confiabilidade, retransmissão, sessão RMC, serialização dos parâmetros específicos do SMM2 ou matchmaking. Eles também não abrem conexão de rede.

O SMM2 é o primeiro alvo de teste. A organização oficial Nextendo publica um servidor NEX dedicado para o jogo: https://github.com/NextendoNetwork/super-mario-maker-2. Isso confirma que existe implementação de servidor para o título, mas o repositório de servidor não fornece por si só o endpoint cliente implantado, a chave de acesso, o mapa DNS/SNI nem todo o fluxo de cliente necessário ao NEX.

Referências de protocolo usadas para a implementação independente:
- NintendoClients, licença MIT: https://github.com/kinnay/NintendoClients
- Schema DataStore_SMM2: https://github.com/kinnay/NintendoClients/blob/master/nintendo/files/proto/datastore_smm2.proto
- Serviço NEX da Nextendo, lado servidor: https://github.com/NextendoNetwork/nextendo-nex

O código do servidor Nextendo foi consultado como referência pública, não incorporado ao cliente.

## 6. DNS, TLS e SNI por título

O overlay agora liga um cache de rotas DNS exatas ao serviço `sfdnsres` do Eden. O Android/JNI pode instalar até 32 mapeamentos explícitos hostname → IPv4. Curingas não são aceitos; uma rota só se aplica quando o hostname consultado coincide exatamente. O hostname original permanece no resultado de resolução para que o jogo mantenha o nome correto ao abrir TLS e enviar SNI.

A proteção de DNS do Eden continua bloqueando domínios Nintendo sem rota. Um domínio bloqueado só passa pelo resolvedor quando o NEX instalou uma rota exata para ele. Não há redirecionamento geral de `*.nintendo.net`.

O NEX ainda não tem os hostnames e IPs de produção do SMM2, nem a confirmação de cobertura dos certificados e do SNI para esses endereços. Os valores locais de exemplo do servidor público não são usados como endpoints de produção. Portanto, o cache permanece vazio até receber o mapa oficial de produção. O endereço/porta do servidor seguro anunciado no LoginEx continua sendo a fonte autoritativa para a segunda conexão.

A validação TLS da plataforma permanece ativa; nenhum certificado é ignorado.

## 7. NAT e P2P

O NEX tem o parser de um probe NNCS de 16 bytes e um tracker de duas observações para classificar mapeamentos iguais, portas diferentes ou observações inconclusivas.

O overlay também liga um cache limitado de sockets UDP à camada de rede nativa do Eden. Enquanto existe um PID Nextendo ativo, sockets UDP vinculados e não conectados podem ser estacionados por cinco segundos quando o jogo os fecha. Se o jogo reabrir a mesma porta no mesmo IP local dentro desse intervalo, o Eden recupera o mesmo socket do host e preserva o mapeamento NAT. O cache é limitado a oito sockets e é limpo em troca/logout de conta ou encerramento da instância de rede. Há testes determinísticos para reuso, endereço incompatível, expiração, substituição, limite e limpeza.

Isso fecha a parte de preservação do socket durante o padrão probe/close/reopen, mas ainda não implementa os sockets de probe NNCS, keepalive periódico, descoberta de peers, sinalização de matchmaking, hole-punching UDP nem teardown coordenado da sessão. O código público do `nextendo-nncs` documenta `10025` e `10125` para o fluxo de Mario Kart 8 (`nncs1-lp1`/`nncs2-lp1`); esses valores não são transferidos ao SMM2. O hostname e as portas NNCS do SMM2 continuam sem contrato público confirmado. O fluxo precisa ser validado em dois dispositivos reais.

## 8. Segurança e bloqueios

- Não copiar Client IDs, tokens, sessões, chaves privadas ou attestations do Den2 ou de qualquer outro emulador.
- Não inventar hostnames, endereços IP, access keys, claims, formatos de pacote ou regras de aceitação.
- Não desativar verificação de certificado TLS.
- Não publicar AppField fictício.
- Usar o PID persistente como NetworkServiceAccountId apenas no mapeamento definido pelo serviço Nextendo; nunca derivar UUID de Switch a partir do PID.
- Não declarar multiplayer funcional sem teste com duas contas NEX, dois dispositivos e um jogo Nextendo suportado.

Bloqueios externos atuais: chave BAAS/attestation específica do NEX; contrato JWT/JWKS; alinhamento das rotas OAuth públicas; NSA/UUID por amigo; endpoint e access key do SMM2; payloads de matchmaking; mapa DNS/TLS/SNI; e fluxo NNCS/P2P do título.
