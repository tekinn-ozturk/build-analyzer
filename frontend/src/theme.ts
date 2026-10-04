import { createTheme } from '@mui/material/styles';

export const monoFont = '"JetBrains Mono", "Cascadia Code", Consolas, "Courier New", monospace';

export const theme = createTheme({
  palette: {
    primary: { main: '#3f51b5' },
    background: { default: '#f5f6f8' },
  },
  shape: { borderRadius: 8 },
  typography: {
    fontFamily: 'Inter, "Segoe UI", Roboto, Helvetica, Arial, sans-serif',
  },
  components: {
    MuiPaper: { defaultProps: { variant: 'outlined' } },
    MuiButton: { defaultProps: { disableElevation: true } },
  },
});
