# IEDNavigator PRO

[🇪🇸 Español](README.md) · [🇬🇧 English](README.en.md) · [🇨🇳 中文](README.zh.md) · **🇧🇷 Português** · [🇸🇦 العربية](README.ar.md)

[![ko-fi](https://ko-fi.com/img/githubbutton_sm.svg)](https://ko-fi.com/mancoqpa)

---

Ferramenta de desktop em **Java** para exploração, simulação e análise do protocolo **IEC 61850**, voltada à capacitação técnica em automação de subestações.
Desenvolvida por **Emilio Medina** (Paraguai). Software livre sob **GPL v3**.

É a versão evoluída (núcleo ativamente mantido) do projeto IEDNavigator, com interface **Java Swing + FlatLaf**.

> ⚠ **Uso exclusivamente educacional.** Não é adequada para testes FAT/SAT, comissionamento ou manobras em instalações em operação. O desenvolvedor não garante o desempenho nem a adequação a qualquer finalidade; o uso é de exclusiva responsabilidade do usuário.

### Funcionalidades

#### Cliente MMS
- Conexão MMS/ACSE a IEDs IEC 61850 (porta padrão 102), com timeout configurável (5–60 s).
- Descoberta do modelo (Server → LD → LN → DO → DA).
- Leitura e escrita de valores por Restrição Funcional (ST, MX, CF, CO, SP, entre outras).
- Sondagem (polling) periódica configurável.
- Monitor de atividade com exportação para CSV.
- Assinatura de relatórios (URCB / BRCB).
- Setting Groups (SGCB) e painel de Ajustes de proteção (SP).
- Construção do modelo por reflexão para IEDs que rejeitam o `retrieveModel` padrão.

#### Controle de manobra
- Modelos de controle (ctlModel): direto, SBO com segurança normal e **SBO com segurança reforçada** (select-with-value). Como o `iec61850bean` 1.9.0 não implementa o select-with-value do modelo reforçado (ctlModel = 4), a troca `SBOw` → `Oper` (com o mesmo `ctlNum`) foi implementada manualmente conforme a IEC 61850-7-2 §20. Verificado em um relé de proteção **NARI PCS-9611S** real.
- Diálogo de manobra com **SBO em duas etapas**: *Selecionar (SBOw)* — com contagem regressiva do temporizador de reserva (`sboTimeout`) —, *Executar (OPER)* e *Cancelar SELECT*.
- Flag `Test`, campo `Check` (`synchroChk` / `interlkChk`), identificador do operador (`orIdent`).
- **Verificação de posição pós-operação**: após um OPERATE aceito, a ferramenta lê o `stVal` do objeto controlado até confirmar (ou não) a manobra física.

#### Modo Servidor / Simulador de IED
- Carregamento de arquivos SCL (ICD / CID / SCD) e instanciação de um servidor IEC 61850.
- Responde a leituras MMS de clientes externos padrão do mercado.
- Edição interativa de valores pela interface.
- Sincronização bidirecional entre o modelo de dados e os publicadores GOOSE.

#### GOOSE (IEC 61850-8-1)
- Publicação e assinatura em Camada 2 (EtherType 0x88B8), com tag VLAN 802.1Q.
- Esquema de retransmissão conforme a norma (número de sequência `sqNum` monotônico).
- Ponte **GOOSE-sobre-UDP** (porta 62746) para redes roteadas / Wi-Fi.
- GOOSE / Sampled Values nativo via `libiec61850` (JNA), incluído em `lib/`.

#### Utilitários SCL e dicionário
- Comparação de arquivos SCL (diferenças por IED, LN, DataSet, GoCB, Report, comunicação).
- Análise do mapa de assinaturas GOOSE (publicadores/assinantes) a partir do SCD.
- Geração de relatório HTML do modelo do IED.
- Dicionário IEC 61850 integrado (descrições de LN, CDC, FC, DO).

### Equipamentos verificados

Equipamentos reais contra os quais a ferramenta foi exercitada, com o que se verificou em cada
um. Os nomes são usados para identificar o produto (uso referencial): não implicam
compatibilidade declarada, homologação nem endosso de nenhum fabricante.

| Equipamento | O que se verificou |
| --- | --- |
| Siemens SIPROTEC 5 7SJ85 | Descoberta do modelo e **manobra SBO com segurança reforçada em campo**, com verificação de posição |
| Siemens SIPROTEC 5 6MD85 | Descoberta do modelo em bancada (31 dispositivos lógicos, 117 nós lógicos, 2.009 objetos de dados) e leitura do modelo de controle |
| NARI PCS-9611S | Descoberta do modelo e controle SBO em laboratório |
| ZIV 2IRX | Descoberta do modelo e **comando direto** (`ctlModel` = 3) |
| ABB REC670 | Descoberta do modelo, blocos de controle de relatório e verificação prévia de condições |
| Ingeteam Ingepac EF-ZTO | Descoberta do modelo |
| Efacec TPU S420 | Descoberta do modelo |

*Descoberta do modelo* significa que a ferramenta recuperou e navegou o modelo de dados do
equipamento; não implica que algo tenha sido operado nele. O ABB REC670 e o Ingeteam estão
retirados de serviço e são usados como bancada de testes: servem para verificar funcionamento,
não para afirmações de desempenho. O 6MD85 foi explorado em bancada, alimentado apenas com tensão auxiliar: sem realimentação de posição nem grandezas medidas.

### Requisitos
- **Windows 10 ou 11 de 64 bits.**
- **Java**: não é necessário instalar se for usado o instalador da seção *Releases* (inclui seu próprio runtime, gerado com `jlink`). Para compilar a partir do código: **JDK 11 ou superior**.
- **Npcap** (somente para captura/publicação GOOSE em Camada 2 e Sampled Values): instalado separadamente em <https://npcap.com/#download> (marcar *WinPcap API-compatible Mode*). Sem o Npcap, o cliente MMS e o servidor funcionam normalmente; apenas o GOOSE/SV em Camada 2 exige essa instalação.

### Instalação e execução

**Opção A — Instalador (recomendado)**
1. Baixar o instalador em [Releases](https://github.com/MancoQpa/IEDnavigatorPRO/releases) (pacote autocontido, inclui o runtime Java).
2. Extrair a pasta inteira (por exemplo, para `C:\IEDNavigatorPRO`).
3. Clique com o botão direito em `INSTALAR.bat` → **Executar como administrador**.
4. Instalar o Npcap em <https://npcap.com/#download> (somente se o GOOSE/SV for utilizado).
5. Iniciar pelo ícone da Área de Trabalho ou pelo `IEDNavigatorPRO.exe`.

**Opção B — Maven (a partir do código)**
```
mvn clean package -DskipTests
java --enable-native-access=ALL-UNNAMED -Djna.library.path=lib \
     -jar target/ied-navigator-1.0.0-jar-with-dependencies.jar
```

**Opção C — Script PowerShell**
```
.\compile.ps1     # compila para classes/ usando lib/*.jar
```
Depois, executar com o `jar` gerado (Opção B) ou com a classe principal `com.iednavigator.IEDNavigatorApp` sobre `classes/` + `lib/*`.

### Uso rápido

**Conectar a um IED real**
1. Selecionar o modo **Cliente** e informar IP e porta (padrão: `102`).
2. Conectar e navegar pela árvore do modelo.
3. Clique com o botão direito em um nó: ler, escrever, adicionar ao monitor, ou operar (FC = CO).

**Simular um IED próprio**
1. Selecionar o modo **Servidor** e carregar um arquivo `.icd` / `.cid` / `.scd`.
2. Iniciar o servidor e conectar-se a partir de qualquer cliente MMS.

**Arquivo de teste incluído:** `test/test_ied.cid`

### Estrutura do projeto
```
src/main/java/com/iednavigator/
  IEDNavigatorApp.java        # GUI principal (Swing)
  IEC61850Client.java         # Cliente MMS/ACSE, descoberta, polling, controle
  IEC61850Server.java         # Servidor/simulador de IED a partir de SCL
  GoosePublisher.java         # Publicação GOOSE (pcap4j)
  GooseSubscriber.java        # Assinatura GOOSE
  GooseUdpBridge.java         # Ponte GOOSE sobre UDP
  ConnectionManager.java      # Gestão de conexão
  SclCompare.java             # Comparação de arquivos SCL
  GooseMapAnalyzer.java       # Mapa de assinaturas GOOSE
  Iec61850Dictionary.java     # Dicionário IEC 61850
  native_lib/                 # Bindings JNA para iec61850.dll (GOOSE/SV nativo)
  [+ painéis e classes auxiliares]
lib/                          # Dependências Java + iec61850.dll
test/test_ied.cid             # CID de teste
```

### Dependências

| Biblioteca                  | Versão  | Licença                | Incluída |
| ---------------------------- | ------- | ----------------------- | -------- |
| iec61850bean                 | 1.9.0   | Apache 2.0              | Sim      |
| libiec61850 (iec61850.dll)   | —       | GPL v3                  | Sim      |
| asn1bean                     | 1.13.0  | Apache 2.0              | Sim      |
| pcap4j                       | 1.8.2   | MIT                     | Sim      |
| FlatLaf                      | 3.2     | Apache 2.0              | Sim      |
| JNA                          | 5.14.0  | Apache 2.0 / LGPL 2.1   | Sim      |
| SLF4J                        | 2.0.9   | MIT                     | Sim      |
| ANTLR                        | 2.7.7   | ANTLR 2 (tipo BSD)      | Sim      |
| Npcap                        | —       | Licença Npcap           | Não (separado) |

Os avisos e licenças completos estão em [THIRD-PARTY-NOTICES.txt](https://github.com/MancoQpa/IEDnavigatorPRO/blob/main/THIRD-PARTY-NOTICES.txt).

### Licença

Distribuído sob a **GNU General Public License v3 (GPL v3)** — ver [LICENSE](https://github.com/MancoQpa/IEDnavigatorPRO/blob/main/LICENSE). Essa condição decorre da biblioteca nativa `libiec61850` (`iec61850.dll`, **GPL v3**), incluída para as funções GOOSE/SV nativas; as demais dependências são permissivas (Apache 2.0, MIT, LGPL/BSD). Você pode usar, modificar e redistribuir o software desde que mantenha a mesma licença, inclua o código-fonte e preserve os avisos de copyright.

O Npcap **não** é redistribuído com este projeto (sua licença não permite); é fornecido um link para o site oficial de download, o que não constitui redistribuição.

Copyright © Emilio Medina.

### Problemas conhecidos e limitações
- A captura/publicação GOOSE em Camada 2 pode não funcionar em todas as interfaces de rede no Windows e requer o Npcap com privilégios de administrador.
- Ferramenta de laboratório e capacitação: **não** validada para colocação em operação produtiva.
- A plataforma é voltada para Windows.
