import DashboardOutlinedIcon from '@mui/icons-material/DashboardOutlined';
import HistoryIcon from '@mui/icons-material/History';
import TroubleshootIcon from '@mui/icons-material/Troubleshoot';
import { Box, List, ListItemButton, ListItemIcon, ListItemText, Typography } from '@mui/material';
import { NavLink } from 'react-router';

const menu = [
  { to: '/', label: 'Dashboard', icon: <DashboardOutlinedIcon /> },
  { to: '/history', label: 'Build History', icon: <HistoryIcon /> },
];

/** Full width on desktop, icons only below the md breakpoint. */
export default function Sidebar() {
  return (
    <Box
      component="nav"
      sx={{
        width: { xs: 64, md: 220 },
        flexShrink: 0,
        bgcolor: '#1f2330',
        color: '#e6e8ee',
      }}
    >
      <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, px: 2.5, py: 2.5 }}>
        <TroubleshootIcon sx={{ color: '#8c9eff' }} />
        <Typography sx={{ display: { xs: 'none', md: 'block' }, fontWeight: 700, fontSize: 15 }}>
          Build Analyzer
        </Typography>
      </Box>
      <List>
        {menu.map((item) => (
          <ListItemButton
            key={item.to}
            component={NavLink}
            to={item.to}
            end
            sx={{
              mx: 1,
              borderRadius: 1,
              color: 'inherit',
              '&.active': { bgcolor: 'rgba(140, 158, 255, 0.18)' },
            }}
          >
            <ListItemIcon sx={{ color: 'inherit', minWidth: { xs: 0, md: 40 } }}>{item.icon}</ListItemIcon>
            <ListItemText primary={item.label} sx={{ display: { xs: 'none', md: 'block' } }} />
          </ListItemButton>
        ))}
      </List>
    </Box>
  );
}
