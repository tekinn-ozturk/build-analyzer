import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import AnalyzeForm from './AnalyzeForm';

function typeUrl(url: string) {
  fireEvent.change(screen.getByLabelText('Jenkins Build URL'), { target: { value: url } });
}

const analyzeButton = () => screen.getByRole<HTMLButtonElement>('button', { name: 'Analyze Build' });

describe('AnalyzeForm', () => {
  it('keeps the button disabled and explains an invalid URL', () => {
    render(<AnalyzeForm onAnalyze={vi.fn()} disabled={false} />);
    typeUrl('https://jenkins.company.com/job/UI-Test/');

    expect(analyzeButton().disabled).toBe(true);
    expect(screen.getByText(/build numarası içermiyor/)).toBeTruthy();
  });

  it('sends the parsed nested job and build number', () => {
    const onAnalyze = vi.fn();
    render(<AnalyzeForm onAnalyze={onAnalyze} disabled={false} />);
    typeUrl('https://jenkins.company.com/job/Team/job/UI/job/Regression/125/');

    expect(screen.getByText('Job: Team/UI/Regression')).toBeTruthy();
    fireEvent.click(analyzeButton());

    expect(onAnalyze).toHaveBeenCalledWith(
      expect.objectContaining({ jobName: 'Team/UI/Regression', buildNumber: 125 }),
    );
  });

  it('does not submit while an analysis is running', () => {
    const onAnalyze = vi.fn();
    render(<AnalyzeForm onAnalyze={onAnalyze} disabled />);

    expect(analyzeButton().disabled).toBe(true);
  });
});
