# Protocolo MStar (obtido da análise do APK SupCam Pro, para interoperabilidade)

- Comandos: `GET http://192.168.1.1/cgi-bin/Config.cgi?action=get|set|del&property=P[&value=V]`
- Resposta: texto, linhas `chave=valor`.
- Pré-visualização: `rtsp://192.168.1.1/liveRTSP/av<id>`, com `<id>` em `Camera.Preview.RTSP.av` (`get Camera.Preview.*`).
- Keep-alive: `set StreamStatus=ON`, repetido a cada poucos segundos (intervalo exato desconhecido; usamos 5 s).
- Menus: `/customer/UI/release/assets/default/raw/menu/user_menus_*` (XML, `<menu command=".." strid="..">` com itens). Valores atuais: `get Camera.Menu.*`.
- Rotação: `Camera.Menu.MovieSpin` = `Ori_0|Ori_90|Ori_180|Ori_270`; `Camera.Menu.ShotVertical`.
- Foto: `set Video=capture`. Gravar: `set Video=recordon|recordoff`. Modo: `set sysworkmode=<valor>`.
- Ficheiros: base SQLite `http://192.168.1.1/tmp/DCF.db` (tabela `DCF`), pasta `/mnt/mmc/DCIM/100MEDIA/` (por implementar).

Por confirmar na câmara real: id RTSP por omissão (usamos 4), formato exato do XML dos menus, intervalo do keep-alive.
