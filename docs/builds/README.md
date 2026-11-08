# XDavi196 Projects & Builds

Portal estático em `docs/builds/`, integrado ao site já existente do NEX.

URL esperada: `https://xdavi196.github.io/NEX/builds/`.

## Publicação sem usar minutos de Actions

Se GitHub Pages estiver habilitado no repositório, configure **Settings → Pages → Deploy from a branch → main → /docs**. O portal não tem processo de build; são apenas HTML, CSS e JavaScript estáticos. A API pública do GitHub carrega releases do NEX e NEX Shop.

Referência oficial para a configuração: https://docs.github.com/en/pages/getting-started-with-github-pages/configuring-a-publishing-source-for-your-github-pages-site

## Forgejo

`https://forgejo.org/releases/` distribui os binários do próprio Forgejo. Não é uma instância de hospedagem de repositórios. Para publicar o mirror `Compila-o-`, é necessário um host Forgejo concreto (ex.: Codeberg ou servidor próprio) e acesso de escrita. O repositório GitHub de compilação permanece privado; este portal não publica o seu conteúdo.
