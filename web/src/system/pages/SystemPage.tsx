import { useState } from 'react';
import { describeError } from '../../shared/api/describeError';
import { Badge } from '../../shared/design/Badge';
import { Button } from '../../shared/design/Button';
import { Card, Page, PageHeader } from '../../shared/design/Layout';
import { useReindex } from '../api';
import { INDEXES, type IndexName } from '../constants';
import styles from './SystemPage.module.css';

type Outcome = { state: 'running' } | { state: 'done'; indexed: number } | { state: 'failed'; message: string };

const documents = (count: number) => `${count.toLocaleString('pt-BR')} ${count === 1 ? 'documento indexado' : 'documentos indexados'}`;

export function SystemPage() {
  const reindex = useReindex();
  const [outcomes, setOutcomes] = useState<Partial<Record<IndexName, Outcome>>>({});
  const running = Object.values(outcomes).some((outcome) => outcome?.state === 'running');

  async function run(index: IndexName) {
    setOutcomes((all) => ({ ...all, [index]: { state: 'running' } }));
    try {
      const { indexed } = await reindex.mutateAsync(index);
      setOutcomes((all) => ({ ...all, [index]: { state: 'done', indexed } }));
    } catch (error) {
      setOutcomes((all) => ({ ...all, [index]: { state: 'failed', message: describeError(error) } }));
    }
  }

  /** One after another: a failure is reported and the rest still run. */
  async function runAll() {
    for (const index of INDEXES) await run(index.key);
  }

  return (
    <Page>
      <PageHeader
        title="Sistema"
        subtitle="Manutenção dos índices de busca. Reindexar recria os documentos de busca a partir do banco."
        actions={<Button onClick={() => void runAll()} busy={running}>{running ? 'Reindexando…' : 'Reindexar tudo'}</Button>}
      />
      <div className={styles.grid}>
        {INDEXES.map((index) => {
          const outcome = outcomes[index.key];
          return (
            <Card key={index.key} title={index.label}
              actions={<Button variant="secondary" size="small" disabled={running} onClick={() => void run(index.key)}>Reindexar</Button>}>
              <p className={styles.hint}>{index.hint}</p>
              {outcome?.state === 'running' && <Badge tone="action">Reindexando</Badge>}
              {outcome?.state === 'done' && <span className={styles.count}>{documents(outcome.indexed)}</span>}
              {outcome?.state === 'failed' && <p role="alert" className={styles.error}>{outcome.message}</p>}
            </Card>
          );
        })}
      </div>
    </Page>
  );
}
