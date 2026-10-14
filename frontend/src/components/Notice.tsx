import { ApiRequestError } from '../api/client';

/** Shows a request failure with the core's error code, or a plain message. */
export function ErrorNotice({ error }: { error: Error | null }) {
  if (!error) return null;
  const detail =
    error instanceof ApiRequestError && error.body ? `${error.body.message} (${error.body.code})` : error.message;
  return (
    <p className="notice error" role="alert">
      {detail.includes('Failed to fetch') || detail.includes('NetworkError')
        ? 'Cannot reach portsync-core. Check that it is running on port 8080.'
        : detail}
    </p>
  );
}
