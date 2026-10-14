import { useEffect, useState, type FormEvent } from 'react';
import { useSearchParams } from 'react-router-dom';
import { ApiRequestError, api } from '../api/client';
import type { ContainerDetail } from '../api/types';
import { ErrorNotice } from '../components/Notice';
import { dateTime, humanise } from '../lib/format';
import { validateContainerNumber } from '../lib/iso6346';

/** Colour-coded parts of an ISO 6346 number: owner, category, serial, check digit. */
export function NumberAnatomy({ value, digitOk }: { value: string; digitOk: boolean }) {
  if (value.length !== 11) return null;
  return (
    <span className="number-anatomy" aria-label={`Owner ${value.slice(0, 3)}, category ${value[3]}, serial ${value.slice(4, 10)}, check digit ${value[10]}`}>
      <span className="owner" title="Owner code">{value.slice(0, 3)}</span>
      <span className="category" title="Equipment category">{value[3]}</span>
      <span className="serial" title="Serial number">{value.slice(4, 10)}</span>
      <span className={`digit${digitOk ? '' : ' bad'}`} title="Check digit">{value[10]}</span>
    </span>
  );
}

export function ContainersPage() {
  const [params, setParams] = useSearchParams();
  const [input, setInput] = useState(params.get('number') ?? '');
  const [result, setResult] = useState<ContainerDetail | null>(null);
  const [error, setError] = useState<Error | null>(null);
  const [notFound, setNotFound] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);

  const validation = validateContainerNumber(input);
  const touched = input.trim().length > 0;

  async function lookup(number: string) {
    setLoading(true);
    setError(null);
    setNotFound(null);
    setResult(null);
    try {
      setResult(await api.container(number));
    } catch (e) {
      if (e instanceof ApiRequestError && e.status === 404) setNotFound(number);
      else setError(e as Error);
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => {
    const fromUrl = params.get('number');
    if (fromUrl && validateContainerNumber(fromUrl).valid) {
      setInput(fromUrl);
      void lookup(validateContainerNumber(fromUrl).normalised);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [params]);

  function onSubmit(e: FormEvent) {
    e.preventDefault();
    if (validation.valid) setParams({ number: validation.normalised });
  }

  return (
    <>
      <header className="page-head">
        <h1>Container lookup</h1>
        <p>Enter an ISO 6346 number. The check digit is verified as you type; a valid number shows where the box is and everything that happened to it.</p>
      </header>

      <form className="lookup" onSubmit={onSubmit} role="search">
        <div>
          <label htmlFor="container-number" className="sr-only">Container number</label>
          <input
            id="container-number"
            value={input}
            onChange={(e) => setInput(e.target.value)}
            placeholder="CSQU3054383"
            autoComplete="off"
            spellCheck={false}
            maxLength={14}
            aria-invalid={touched && !validation.valid}
            aria-describedby="container-check"
          />
          <p id="container-check" className={`check ${touched ? (validation.valid ? 'tone-ok' : 'tone-crit') : 'muted'}`}>
            {!touched ? 'Example: CSQU3054383' : validation.valid ? 'Valid ISO 6346 number' : validation.reason}
          </p>
        </div>
        <button className="btn primary" type="submit" disabled={!validation.valid || loading}>
          {loading ? 'Looking up' : 'Look up'}
        </button>
      </form>
      {touched && validation.normalised.length === 11 && (
        <div style={{ marginTop: '1rem' }}>
          <NumberAnatomy value={validation.normalised} digitOk={validation.valid} />
        </div>
      )}

      <ErrorNotice error={error} />
      {notFound && (
        <p className="notice section">{notFound} is a valid number, but it has never been seen at this terminal.</p>
      )}

      {result && (
        <section className="section" aria-labelledby="result-heading">
          <h2 id="result-heading" className="code">{result.number}</h2>
          <div className="panel" style={{ marginTop: '0.85rem' }}>
            <dl className="facts">
              <div><dt>Status</dt><dd>{humanise(result.status)}</dd></div>
              <div><dt>Location</dt><dd>{result.location ? result.location.label : 'Not in the yard'}</dd></div>
              <div><dt>Vessel</dt><dd className="code">{result.vesselId ?? '–'}</dd></div>
              <div><dt>Last event</dt><dd>{dateTime(result.lastEventAt)}</dd></div>
            </dl>
            <h3 style={{ padding: '0 1.25rem 0.5rem' }}>History</h3>
            {result.history.length === 0 ? (
              <p className="empty">No applied events.</p>
            ) : (
              <ol className="timeline">
                {result.history.map((h, i) => (
                  <li key={`${h.eventId ?? 'x'}-${i}`}>
                    <span className="muted">{dateTime(h.occurredAt)}</span>
                    <span>{humanise(h.type)}</span>
                    <span>{h.description}</span>
                  </li>
                ))}
              </ol>
            )}
            {result.rejections.length > 0 && (
              <>
                <h3 style={{ padding: '0 1.25rem 0.5rem' }}>Rejected events</h3>
                <ol className="timeline">
                  {result.rejections.map((r, i) => (
                    <li key={`${r.eventId ?? 'r'}-${i}`}>
                      <span className="muted">{dateTime(r.deadLetteredAt)}</span>
                      <span className="tone-crit">{humanise(r.reason)}</span>
                      <span>{r.detail}</span>
                    </li>
                  ))}
                </ol>
              </>
            )}
          </div>
        </section>
      )}
    </>
  );
}
