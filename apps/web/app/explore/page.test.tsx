// @vitest-environment jsdom
import { cleanup, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import { PlanningProvider } from '../../components/planning-provider';
import Page from './page';

afterEach(cleanup);

function renderPage(element: React.ReactElement) {
  return render(<PlanningProvider>{element}</PlanningProvider>);
}

describe('Explore page query boundary', () => {
  it('renders default search when searchParams is undefined or empty', async () => {
    const element = await Page({});
    renderPage(element);
    expect((screen.getByLabelText('Search places') as HTMLInputElement).value).toBe('');
  });

  it('renders with single query parameter', async () => {
    const element = await Page({ searchParams: Promise.resolve({ q: 'hampi' }) });
    renderPage(element);
    expect((screen.getByLabelText('Search places') as HTMLInputElement).value).toBe('hampi');
  });

  it('normalizes repeated query parameters to the first value without crashing', async () => {
    const element = await Page({ searchParams: Promise.resolve({ q: ['coast', 'hills'] }) });
    renderPage(element);
    expect((screen.getByLabelText('Search places') as HTMLInputElement).value).toBe('coast');
  });

  it('safely handles empty array query parameter as empty search', async () => {
    const element = await Page({ searchParams: Promise.resolve({ q: [] }) });
    renderPage(element);
    expect((screen.getByLabelText('Search places') as HTMLInputElement).value).toBe('');
  });
});
