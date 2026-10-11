import { beforeEach, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import '../../i18n';
import i18n from '../../i18n';
import { ActionMenu } from './ActionMenu';
import { PageHeader } from './PageHeader';
import { Button } from './Button';
const selected=vi.fn();
const items=[{id:'first',label:'Export',onSelect:selected,icon:'download'}, {id:'disabled',label:'Unavailable',disabled:true,onSelect:selected}, {id:'last',label:'Mode',pressed:true,onSelect:selected}];
beforeEach(async()=>{await i18n.changeLanguage('en');selected.mockClear();});
it('opens by keyboard, skips disabled items, wraps and restores focus with Escape', async()=>{
 const user=userEvent.setup();render(<ActionMenu items={items}/>);const trigger=screen.getByRole('button',{name:'More actions'});trigger.focus();
 await user.keyboard('{ArrowDown}');expect(screen.getByRole('menuitem',{name:'Export'})).toHaveFocus();
 await user.keyboard('{ArrowDown}');expect(screen.getByRole('menuitemcheckbox',{name:'Mode'})).toHaveFocus();
 await user.keyboard('{ArrowDown}');expect(screen.getByRole('menuitem',{name:'Export'})).toHaveFocus();
 await user.keyboard('{End}');expect(screen.getByRole('menuitemcheckbox',{name:'Mode'})).toHaveFocus();
 await user.keyboard('{Home}');expect(screen.getByRole('menuitem',{name:'Export'})).toHaveFocus();
 await user.keyboard('{Escape}');expect(screen.queryByRole('menu')).not.toBeInTheDocument();expect(trigger).toHaveFocus();
 await user.keyboard('{ArrowUp}');expect(screen.getByRole('menuitemcheckbox',{name:'Mode'})).toHaveFocus();
 await user.keyboard('{ArrowUp}');expect(screen.getByRole('menuitem',{name:'Export'})).toHaveFocus();
});
it('executes a selected action once and closes, with checkbox state and disabled behavior',async()=>{
 const user=userEvent.setup();render(<ActionMenu items={items} label="Response actions" side="up"/>);
 await user.click(screen.getByRole('button',{name:'Response actions'}));
 expect(screen.getByRole('menuitemcheckbox')).toHaveAttribute('aria-checked','true');
 await user.click(screen.getByRole('menuitem',{name:'Unavailable'}));expect(selected).not.toHaveBeenCalled();
 await user.click(screen.getByRole('menuitem',{name:'Export'}));expect(selected).toHaveBeenCalledOnce();expect(screen.queryByRole('menu')).not.toBeInTheDocument();
 expect(screen.getByRole('button',{name:'Response actions'})).toHaveFocus();
});
it('dismisses on outside click and Tab, preserving access to the primary action',async()=>{
 const user=userEvent.setup();render(<PageHeader title="Documents" context="12 documents" secondaryActions={items} primaryAction={<Button>Classify</Button>}/>);
 const trigger=screen.getByRole('button',{name:'More actions'});
 await user.click(trigger);await user.click(screen.getByRole('heading',{name:'Documents'}));expect(screen.queryByRole('menu')).not.toBeInTheDocument();
 await user.click(trigger);await user.tab();expect(screen.queryByRole('menu')).not.toBeInTheDocument();expect(screen.getByRole('button',{name:'Classify'})).toHaveFocus();
});
it('shows no empty menu and keeps title, description, legacy actions and primary action usable',()=>{
 render(<PageHeader title="Page" kicker="Context" description="Description" actions={<Button>Legacy</Button>} primaryAction={<Button>Primary</Button>}/>);
 expect(screen.getByRole('heading',{name:'Page'})).toBeInTheDocument();expect(screen.getByText('Description')).toBeInTheDocument();
 expect(screen.getByRole('button',{name:'Legacy'})).toBeInTheDocument();expect(screen.getByRole('button',{name:'Primary'})).toBeInTheDocument();expect(screen.queryByRole('button',{name:'More actions'})).not.toBeInTheDocument();
});
it('handles a menu containing only disabled actions without executing one',async()=>{
 const user=userEvent.setup();render(<ActionMenu items={[{id:'disabled',label:'Unavailable',disabled:true,danger:true,onSelect:selected}]}/>);
 await user.click(screen.getByRole('button',{name:'More actions'}));await user.keyboard('{ArrowDown}');expect(selected).not.toHaveBeenCalled();
 await user.click(screen.getByRole('button',{name:'More actions'}));expect(screen.queryByRole('menu')).not.toBeInTheDocument();
});
