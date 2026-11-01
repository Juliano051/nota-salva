/**
 * Nota do Mercado — recebe as notas enviadas pelo app e grava nesta planilha.
 *
 * Como instalar (resumo — o passo a passo completo está no LEIAME):
 *  1. Crie uma planilha nova no Google Sheets.
 *  2. Extensões › Apps Script, apague o conteúdo e cole este arquivo.
 *  3. Selecione a função "configurar" e clique em Executar (autorize o acesso).
 *  4. Implantar › Nova implantação › App da Web:
 *       Executar como: Eu  |  Quem pode acessar: Qualquer pessoa
 *  5. Copie a URL (termina em /exec) e o TOKEN (aparece no registro de execução) para o app.
 */

const ABA_LANC = 'Lançamentos';
const ABA_RESUMO = 'Resumo mensal';
const PASTA_FOTOS = 'Nota do Mercado - fotos';
const CABECALHO = ['Data da compra', 'Estabelecimento', 'CNPJ', 'Endereço', 'Total (R$)',
                   'Mês', 'Chave NFC-e', 'Foto', 'Registrado em'];

/** Rode uma vez pelo editor. Cria as abas, o resumo mensal, a pasta das fotos e o token. */
function configurar() {
  const ss = SpreadsheetApp.getActiveSpreadsheet();

  const sh = ss.getSheetByName(ABA_LANC) || ss.insertSheet(ABA_LANC, 0);
  sh.getRange(1, 1, 1, CABECALHO.length).setValues([CABECALHO])
    .setFontWeight('bold').setBackground('#c8e6c9');
  sh.setFrozenRows(1);
  sh.getRange('A2:A').setNumberFormat('dd/mm/yyyy');
  sh.getRange('E2:E').setNumberFormat('"R$" #,##0.00');
  sh.getRange('I2:I').setNumberFormat('dd/mm/yyyy hh:mm');
  sh.setColumnWidth(2, 240);
  sh.setColumnWidth(4, 280);

  const rs = ss.getSheetByName(ABA_RESUMO) || ss.insertSheet(ABA_RESUMO, 1);
  rs.clear();
  rs.getRange('A1').setFormula(
    "=QUERY('" + ABA_LANC + "'!A2:I, \"select F, sum(E), count(E) where E is not null " +
    "group by F order by F desc label F 'Mês', sum(E) 'Total no mês (R$)', count(E) 'Compras'\", 0)");
  rs.getRange('B2:B').setNumberFormat('"R$" #,##0.00');
  rs.setColumnWidth(2, 160);

  // Remove a "Página1" vazia que vem na planilha nova
  const vazia = ss.getSheetByName('Página1') || ss.getSheetByName('Sheet1');
  if (vazia && vazia.getLastRow() === 0 && ss.getSheets().length > 2) ss.deleteSheet(vazia);

  const props = PropertiesService.getScriptProperties();
  let token = props.getProperty('TOKEN');
  if (!token) {
    token = Utilities.getUuid().replace(/-/g, '').slice(0, 16);
    props.setProperty('TOKEN', token);
  }
  obterPasta_();
  Logger.log('Tudo pronto! TOKEN para colar no app: ' + token);
}

/** O app envia cada nota aqui (POST). */
function doPost(e) {
  const lock = LockService.getScriptLock();
  try {
    lock.waitLock(20000);
    const req = JSON.parse(e.postData.contents);
    validarToken_(req.token);

    const ss = SpreadsheetApp.getActiveSpreadsheet();
    const sh = ss.getSheetByName(ABA_LANC);
    if (!sh) throw new Error('Rode a função "configurar" no editor do Apps Script primeiro.');

    const partes = String(req.data || '').split('/').map(Number);
    if (partes.length !== 3 || !partes[2]) throw new Error('Data inválida: ' + req.data);
    const [dia, mesNum, ano] = partes;
    const data = new Date(ano, mesNum - 1, dia, 12, 0, 0);
    const mes = ano + '-' + String(mesNum).padStart(2, '0');
    const total = Number(req.total);
    if (!(total > 0)) throw new Error('Total inválido: ' + req.total);

    if (!req.forcar) {
      const linha = acharDuplicata_(sh, req.chave, data, total, req.cnpj);
      if (linha) {
        return json_({ ok: false, duplicata: true,
          msg: 'Parece que esta nota já foi lançada (linha ' + linha + ' da planilha). Gravar de novo?' });
      }
    }

    let linkFoto = '';
    if (req.fotoBase64) {
      const nome = mes + '-' + String(dia).padStart(2, '0') + ' ' +
        String(req.estabelecimento || 'nota').slice(0, 40) + ' R$' + total.toFixed(2) + '.jpg';
      const blob = Utilities.newBlob(Utilities.base64Decode(req.fotoBase64), 'image/jpeg', nome);
      linkFoto = obterPasta_().createFile(blob).getUrl();
    }

    // O apóstrofo força texto (evita que o Sheets transforme "2026-11" em data
    // ou a chave de 44 dígitos em número).
    sh.appendRow([
      data,
      req.estabelecimento || '',
      req.cnpj ? "'" + req.cnpj : '',
      req.endereco || '',
      total,
      "'" + mes,
      req.chave ? "'" + req.chave : '',
      linkFoto,
      new Date()
    ]);

    const r = resumoMes_(sh, mes);
    return json_({ ok: true, mes: mes, totalMes: r.total, compras: r.compras, planilha: ss.getUrl() });
  } catch (err) {
    return json_({ ok: false, msg: String((err && err.message) || err) });
  } finally {
    lock.releaseLock();
  }
}

/** O app consulta aqui o total do mês (GET ?token=...&mes=AAAA-MM). */
function doGet(e) {
  try {
    validarToken_(e.parameter.token);
    const ss = SpreadsheetApp.getActiveSpreadsheet();
    const sh = ss.getSheetByName(ABA_LANC);
    if (!sh) throw new Error('Rode a função "configurar" no editor do Apps Script primeiro.');
    const mes = e.parameter.mes ||
      Utilities.formatDate(new Date(), ss.getSpreadsheetTimeZone(), 'yyyy-MM');
    const r = resumoMes_(sh, mes);
    return json_({ ok: true, mes: mes, totalMes: r.total, compras: r.compras, planilha: ss.getUrl() });
  } catch (err) {
    return json_({ ok: false, msg: String((err && err.message) || err) });
  }
}

// ---------------------------------------------------------------- auxiliares

function validarToken_(token) {
  const certo = PropertiesService.getScriptProperties().getProperty('TOKEN');
  if (!certo || token !== certo) throw new Error('Token inválido. Confira o token nas configurações do app.');
}

function resumoMes_(sh, mes) {
  const n = sh.getLastRow() - 1;
  if (n < 1) return { total: 0, compras: 0 };
  const vals = sh.getRange(2, 5, n, 2).getValues(); // colunas E (total) e F (mês)
  let total = 0, compras = 0;
  vals.forEach(function (v) {
    if (String(v[1]) === mes && Number(v[0]) > 0) { total += Number(v[0]); compras++; }
  });
  return { total: Math.round(total * 100) / 100, compras: compras };
}

function acharDuplicata_(sh, chave, data, total, cnpj) {
  const n = sh.getLastRow() - 1;
  if (n < 1) return 0;
  const vals = sh.getRange(2, 1, n, 7).getValues();
  for (let i = 0; i < vals.length; i++) {
    const v = vals[i];
    if (chave && String(v[6]) === String(chave)) return i + 2;
    const mesmoDia = v[0] instanceof Date &&
      v[0].getFullYear() === data.getFullYear() &&
      v[0].getMonth() === data.getMonth() &&
      v[0].getDate() === data.getDate();
    const mesmoValor = Math.abs(Number(v[4]) - total) < 0.005;
    const mesmaLoja = !cnpj || !v[2] || String(v[2]) === String(cnpj);
    if (mesmoDia && mesmoValor && mesmaLoja) return i + 2;
  }
  return 0;
}

function obterPasta_() {
  const props = PropertiesService.getScriptProperties();
  const id = props.getProperty('PASTA_ID');
  if (id) {
    try { return DriveApp.getFolderById(id); } catch (e) { /* pasta apagada: cria outra */ }
  }
  const pasta = DriveApp.createFolder(PASTA_FOTOS);
  props.setProperty('PASTA_ID', pasta.getId());
  return pasta;
}

function json_(obj) {
  return ContentService.createTextOutput(JSON.stringify(obj))
    .setMimeType(ContentService.MimeType.JSON);
}
