# XDavi196 Projects & Builds

Portal estático em `docs/builds/`, integrado ao site já existente do NEX.

URL do portal após ativar GitHub Pages: `https://xdavi196.github.io/NEX/builds/`.

## Ativar a publicação

Na última verificação, a API do GitHub informou `has_pages: false` para `XDavi196/NEX`. Portanto, os arquivos estão no `main`, mas o site ainda não está publicado. Para ativá-lo uma vez, alguém com permissão de administrador deve abrir **Settings → Pages**, selecionar **Deploy from a branch**, escolher `main` e a pasta `/docs`, e salvar.

O site usa apenas HTML, CSS e JavaScript estáticos; não precisa compilar o site nem criar um workflow próprio. O método de publicação por branch está documentado pelo GitHub: https://docs.github.com/en/pages/getting-started-with-github-pages/configuring-a-publishing-source-for-your-github-pages-site

## Dados mostrados

- O portal consulta as releases públicas do NEX e NEX Shop diretamente pela API pública do GitHub.
- `XDavi196/Compila-o-` permanece privado; a página mostra o projeto e o link do repositório, mas não expõe seu código nem fabrica um APK público.
- Nenhum código AOT, ROM ou asset proprietário é publicado pelo portal.

## Forgejo

`https://forgejo.org/releases/` distribui os binários do próprio Forgejo. Não é uma instância para hospedar repositórios de terceiros. Para criar o espelho `Compila-o-`, é necessário escolher uma instância Forgejo concreta e criar o repositório de destino. Codeberg utiliza Forgejo, mas não oferece hospedagem privada; como `Compila-o-` está privado, manter essa privacidade exige uma instância própria ou outro provedor que aceite repos privados. Tornar o código público requer autorização explícita e revisão da licença. Nenhuma cópia do repositório privado foi enviada para outro serviço.


## Portal Compila-o-

Página dedicada inspirada na organização de releases do Forgejo: `docs/builds/compila-o/`. Ela apresenta a build Android #57 como build de CI verificada, mas não como release pública. O repositório original continua privado e nenhum código ou ROM é exposto. URL esperada: `https://xdavi196.github.io/NEX/builds/compila-o/`.
