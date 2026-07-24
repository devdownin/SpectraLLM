import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { Field, Input, Select, Textarea } from './Field';
import { Table, TableHead, TableBody, TableRow, Th, Td } from './Table';

describe('Field', () => {
  it('links the label to the control and forwards changes', () => {
    const onChange = vi.fn();
    render(
      <Field label="VRAM">
        {(field) => <Input {...field} value="" onChange={onChange} placeholder="auto" />}
      </Field>,
    );
    const input = screen.getByLabelText('VRAM');
    fireEvent.change(input, { target: { value: '12G' } });
    expect(onChange).toHaveBeenCalled();
  });

  it('shows the description and wires aria-describedby', () => {
    render(
      <Field label="RAM" description="Empty = detected automatically">
        {(field) => <Input {...field} />}
      </Field>,
    );
    const input = screen.getByLabelText('RAM');
    const desc = screen.getByText('Empty = detected automatically');
    expect(input).toHaveAttribute('aria-describedby', desc.id);
  });

  it('shows the error instead of the description and flags the control invalid', () => {
    render(
      <Field label="Cores" description="hidden when error" error="Must be a number">
        {(field) => <Input {...field} />}
      </Field>,
    );
    expect(screen.getByRole('alert')).toHaveTextContent('Must be a number');
    expect(screen.queryByText('hidden when error')).toBeNull();
    expect(screen.getByLabelText('Cores')).toHaveAttribute('aria-invalid', 'true');
  });

  it('marks required fields on the label', () => {
    render(
      <Field label="Name" required>
        {(field) => <Input {...field} />}
      </Field>,
    );
    expect(screen.getByText('*')).toBeInTheDocument();
  });
});

describe('Select / Textarea', () => {
  it('renders a working select with options', () => {
    const onChange = vi.fn();
    render(
      <Select aria-label="Filter" value="a" onChange={onChange}>
        <option value="a">A</option>
        <option value="b">B</option>
      </Select>,
    );
    fireEvent.change(screen.getByLabelText('Filter'), { target: { value: 'b' } });
    expect(onChange).toHaveBeenCalled();
  });

  it('renders a textarea flagged invalid', () => {
    render(<Textarea aria-label="Notes" invalid />);
    expect(screen.getByLabelText('Notes')).toHaveAttribute('aria-invalid', 'true');
  });
});

describe('Table', () => {
  it('renders a semantic table with header and rows', () => {
    render(
      <Table>
        <TableHead>
          <tr><Th>Model</Th><Th>Status</Th></tr>
        </TableHead>
        <TableBody>
          <TableRow><Td>llama-3</Td><Td>Completed</Td></TableRow>
          <TableRow><Td>mistral</Td><Td>Failed</Td></TableRow>
        </TableBody>
      </Table>,
    );
    expect(screen.getByRole('table')).toBeInTheDocument();
    expect(screen.getAllByRole('columnheader')).toHaveLength(2);
    expect(screen.getAllByRole('row')).toHaveLength(3);
    expect(screen.getByRole('columnheader', { name: 'Model' })).toHaveAttribute('scope', 'col');
  });

  it('fires row onClick', () => {
    const onClick = vi.fn();
    render(
      <Table>
        <TableBody>
          <TableRow onClick={onClick}><Td>row</Td></TableRow>
        </TableBody>
      </Table>,
    );
    fireEvent.click(screen.getByText('row'));
    expect(onClick).toHaveBeenCalledTimes(1);
  });
});
