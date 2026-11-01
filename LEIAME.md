# Nota do Mercado

App Android que fotografa a nota fiscal (NFC-e/cupom) do supermercado, extrai **total, data, estabelecimento, endereço e CNPJ** e grava numa **planilha Google**, que soma automaticamente o gasto de cada mês.

## Como funciona

```
Foto da nota ──► OCR + leitura do QR Code (no próprio celular, ML Kit)
             ──► você confere/corrige os campos
             ──► POST para o Apps Script da sua planilha (na sua conta Google)
                    ├─ grava a linha na aba "Lançamentos"
                    ├─ salva a foto numa pasta do seu Google Drive
                    └─ devolve o total do mês, que aparece no topo do app
```

- **QR Code da NFC-e**: quando o app consegue ler o QR, ele obtém a chave de acesso, que dá o CNPJ e o mês com 100% de certeza (e o valor, nas notas emitidas em contingência).
- **Duplicatas**: se a mesma chave (ou mesma data + valor + CNPJ) já estiver na planilha, o app pergunta antes de gravar de novo.
- **Aba "Resumo mensal"**: total gasto e número de compras por mês, atualizada sozinha.
- Também dá para escolher uma foto da galeria ou usar **Compartilhar › Nota do Mercado** em qualquer imagem.

## Por que Apps Script (e não login do Google dentro do app)

Gravar direto pela API do Sheets exigiria criar um projeto no Google Cloud, configurar tela de consentimento OAuth e registrar a impressão digital do APK. Com o Apps Script, o script roda **na sua própria conta Google**, com acesso à planilha e ao Drive, e o app só precisa da URL e de um token secreto. É bem mais simples para uso pessoal/familiar.

---

## Parte 1 — Planilha e Apps Script (≈ 5 min)

1. Crie uma planilha nova em <https://sheets.new> e dê um nome (ex.: "Gastos com mercado").
2. Menu **Extensões › Apps Script**. Apague o que estiver no editor e cole todo o conteúdo de `apps-script/Codigo.gs`. Salve (ícone de disquete).
3. No seletor de funções (ao lado de "Depurar"), escolha **configurar** e clique **Executar**.
   - O Google vai pedir autorização: escolha sua conta › *Avançado* › *Acessar projeto (não seguro)* › *Permitir*. (O aviso aparece porque o script é seu e não foi verificado pelo Google.)
   - Ao terminar, o **Registro de execução** mostra: `TOKEN para colar no app: xxxxxxxx`. Copie esse token.
4. **Implantar › Nova implantação** › ícone de engrenagem › **App da Web**:
   - Executar como: **Eu**
   - Quem pode acessar: **Qualquer pessoa**
   - Clique **Implantar** e copie a **URL do App da Web** (termina em `/exec`).

> "Qualquer pessoa" é necessário para o app conseguir chamar o script sem login; o token impede que outras pessoas gravem na sua planilha. Não compartilhe a URL + token.

> Se você alterar o código depois, use **Implantar › Gerenciar implantações › Editar › Versão: Nova versão** para manter a mesma URL.

## Parte 2 — Gerar o APK

### Opção A: GitHub Actions (sem instalar nada no computador)

1. Crie um repositório **privado** no GitHub e envie o conteúdo desta pasta (pode arrastar os arquivos em *Add file › Upload files*, inclusive a pasta `.github`).
2. Abra a aba **Actions** do repositório. O fluxo **Gerar APK** roda sozinho a cada envio (ou clique em *Run workflow*).
3. Em ~5 minutos, abra a execução concluída e baixe **NotaDoMercado-apk** (vem em .zip; dentro está o `.apk`).

### Opção B: Android Studio

Abra a pasta no Android Studio, espere a sincronização do Gradle e use **Build › Build App Bundle(s) / APK(s) › Build APK(s)**.

## Parte 3 — Instalar e conectar

1. Passe o `.apk` para o celular e abra. O Android vai pedir para **permitir instalação de fontes desconhecidas** para o app que você usou para abrir (Arquivos, Chrome, Drive…).
2. Na primeira abertura aparece **Conectar à planilha**: cole a URL `/exec` e o token.
3. O cartão do topo deve mostrar "Mercado em novembro de 2026 — R$ 0,00". Pronto: fotografe uma nota.

### Dicas para a foto
- Nota esticada, boa luz, sem sombra do celular; enquadre do cabeçalho até o QR Code.
- Cupom muito longo: priorize o topo (loja/endereço) e o rodapé (total, data, QR Code).

## Colunas da planilha

| Data da compra | Estabelecimento | CNPJ | Endereço | Total (R$) | Mês | Chave NFC-e | Foto | Registrado em |
|---|---|---|---|---|---|---|---|---|

Para totais por mês fora do resumo, use por exemplo: `=SOMASES('Lançamentos'!E:E; 'Lançamentos'!F:F; "2026-11")`.

## Estrutura do projeto

```
app/src/main/java/com/notamercado/app/
  MainActivity.kt   tela, câmera, envio para a planilha
  NotaParser.kt     extração de total/data/loja/endereço/CNPJ e leitura do QR da NFC-e
  ImagemUtil.kt     redução e rotação da foto antes de enviar ao Drive
app/src/test/...    testes do extrator (rodam no GitHub Actions)
apps-script/Codigo.gs  backend que grava na planilha e no Drive
.github/workflows/gerar-apk.yml  compilação automática do APK
```

A assinatura do APK usa a chave `app/nota-mercado.keystore` (senha `notamercado`), para que atualizações instalem por cima da versão anterior. É adequada para uso pessoal; para publicar na Play Store, gere uma chave própria e não a deixe no repositório.
