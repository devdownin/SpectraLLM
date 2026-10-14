import { expect,it } from 'vitest';
import {render,screen} from '@testing-library/react';
import '../../i18n';
import i18n from '../../i18n';
import {StatusBadge} from './StatusBadge';
it('renders translated labels and preserves unknown backend states',async()=>{
 await i18n.changeLanguage('fr');render(<><StatusBadge status="CANCELLED"/><StatusBadge status="partial"/><StatusBadge status="QUALIFIED" domain="document"/><StatusBadge status="FUTURE_STATE"/><StatusBadge/></>);
 expect(screen.getByText('Annulé')).toBeInTheDocument();expect(screen.getByText('Succès partiel')).toBeInTheDocument();expect(screen.getByText('Qualifié')).toBeInTheDocument();expect(screen.getByText('FUTURE_STATE')).toBeInTheDocument();expect(screen.getByText('Inconnu')).toBeInTheDocument();
});
