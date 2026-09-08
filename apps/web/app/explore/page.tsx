import { Explore } from '../../components/explore';
export const metadata = { title: 'Explore' };
export default async function Page({ searchParams }: { searchParams: Promise<{ q?: string }> }) {
  const params = await searchParams;
  return <Explore initialQuery={params.q ?? ''} />;
}
