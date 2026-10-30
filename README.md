# Relógio do Tempo — Fabric 1.20.1

Projeto-fonte para Minecraft Java 1.20.1, Fabric e Java 17. O item se chama **Relógio do Tempo**.

## Teclas

- **R**: alterna pausa/continuação. Na pausa normal, o mundo para, mas o jogador pode andar.
- **Y**: alterna o congelamento completo (incluindo o jogador); teste em mundo de cópia, pois o congelamento do jogador depende dos pontos de tick interceptados pelo mod.
- **Segurar F**: rebobina os estados gravados. Ao soltar F, o mundo e o jogador ficam congelados no ponto histórico escolhido.
- **Segurar G**: reproduz o futuro gravado a 3 quadros históricos por tick. Ao soltar G, o mundo fica congelado nesse ponto.
- A tecla **F** não troca o item para a mão secundária enquanto o Relógio do Tempo estiver na mão principal. A tecla original de troca é restaurada quando você deixa de segurar o relógio.

O histórico atual guarda até **400 quadros (aproximadamente 20 segundos)**; ainda não é um histórico persistente de 30 minutos e não deve ser tratado como rollback perfeito. Ele registra NBT de entidades carregadas, estados de blocos alterados, NBT dos block entities carregados (por exemplo, inventários de baús/fornalhas quando o block entity é acompanhado) e horário do dia. Isso pode desfazer dano/vida de mobs, reconstruir entidades que morreram, reverter blocos e recuperar muitos campos salvos de villagers, como dados de profissão, dependendo do estado salvo.

## Limites importantes

Isto é um sistema experimental de snapshots, não uma gravação literal de cada byte do motor do Minecraft. Durante a pausa, ele tenta parar entidades, atualizações de block entities, ticks agendados de blocos/fluidos, ticks aleatórios de chunks e spawns normais. No flashback, ele **não consegue rebobinar exatamente animações visuais já renderizadas**, sons ou partículas já apresentados. As filas internas de ticks agendados são congeladas, mas seus conteúdos não são restaurados historicamente; isso pode causar diferenças em redstone/fluidos após retomar. Dados globais como reivindicações de POI de aldeões, mudanças internas específicas de mods e entidades de mods que não conseguem ser reconstruídas por NBT também podem não voltar perfeitamente. Só são gravadas entidades e block entities em chunks carregados; o mod evita forçar o carregamento de chunks. Jogadores são restaurados no mesmo dimension em que o snapshot foi feito; voltar entre dimensões não é garantido. Em servidores com muitos mobs/block entities, os snapshots podem consumir bastante RAM.

Faça cópia de segurança dos mundos antes de testar. Não use inicialmente em um mundo importante ou em servidor público.

## Como compilar no Windows

1. Instale um JDK **17** (Minecraft 1.20.1 / Fabric Loom deste projeto usa Java 17).
2. Instale o Gradle 8.7 ou abra o projeto em uma IDE com suporte ao Gradle.
3. Na pasta deste projeto, execute `gradle build` ou clique em `build.bat`.
4. O JAR de saída fica em `build/libs/`.
5. Copie o JAR compilado para a pasta `mods` de uma instalação Fabric **Minecraft 1.20.1**, junto com Fabric API compatível.

Se não tiver Gradle instalado, o workflow do GitHub incluído neste projeto pode compilar automaticamente depois de você enviar a pasta do projeto para um repositório. Vá em **Actions** e baixe o artefato `relogio-do-tempo-build` da execução concluída.

## Receita

O relógio pode ser fabricado com obsidiana, blocos de ouro, um relógio vanilla e um diamante.

## Estrutura

- `src/main/java/com/enzo/timeclock/TimeClockMod.java`: registro do item e pacote de controle.
- `src/main/java/com/enzo/timeclock/client/TimeClockClient.java`: teclas R/F/G e bloqueio da troca de mãos em F enquanto o relógio está na mão principal.
- `src/main/java/com/enzo/timeclock/time/TimeManager.java`: buffer de histórico, snapshots e reprodução para trás/à frente.
- `src/main/java/com/enzo/timeclock/mixin/`: pontos de interceptação para tick do mundo, entidades, blocos, block entities, ticks agendados e atalhos do cliente.
- `src/main/resources/`: configuração Fabric, Mixins, nome do item e modelo.

## Licença

MIT. Consulte `LICENSE`.
