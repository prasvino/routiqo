import { Explore } from '../../components/explore';
export const metadata = { title: 'Explore' };

export default async function Page({
  searchParams,
}: {
  searchParams?: Promise<{ q?: string | string[] }>;
}) {
  const params = (await searchParams) ?? {};
  const query = Array.isArray(params.q) ? (params.q[0] ?? '') : (params.q ?? '');
  return <Explore initialQuery={query} />;
}
