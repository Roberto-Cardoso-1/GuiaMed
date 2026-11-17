-- Rodar com a conexão da API no banco descartável da demonstração.
BEGIN;
SET LOCAL ROLE rl_app_ia;
SELECT guiamed_api.register_allergy(
    encode(sha256(convert_to('guiamed-demo-academica-perfil-a', 'UTF8')), 'hex'),
    'substancia-ficticia-demo'
);
COMMIT;
