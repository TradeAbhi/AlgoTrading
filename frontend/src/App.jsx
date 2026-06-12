import { useState } from 'react';
import UsWeeklyBreakout from './components/UsWeeklyBreakout';
import WeeklyBreakout from './components/WeeklyBreakout';
import IpoScanner from './components/IpoScanner';
import DeltaScanner from './components/DeltaScanner';
import Backtest from './components/Backtest';

function App() {
  const [activeScanner, setActiveScanner] = useState('us-weekly');

  return (
    <div style={styles.container}>
      <h1 style={styles.title}>🚀 Algo Trading Dashboard</h1>
      
      {/* Scanner Navigation */}
      <div style={styles.scannerNav}>
        <button
          onClick={() => setActiveScanner('us-weekly')}
          style={{
            ...styles.navButton,
            ...(activeScanner === 'us-weekly' ? styles.activeNavButton : {})
          }}
        >
          🇺🇸 US Weekly Breakout
        </button>
        <button
          onClick={() => setActiveScanner('nse-weekly')}
          style={{
            ...styles.navButton,
            ...(activeScanner === 'nse-weekly' ? styles.activeNavButton : {})
          }}
        >
          🇮🇳 NSE Weekly Breakout
        </button>
        <button
          onClick={() => setActiveScanner('ipo')}
          style={{
            ...styles.navButton,
            ...(activeScanner === 'ipo' ? styles.activeNavButton : {})
          }}
        >
          📈 IPO Scanner
        </button>
        <button
          onClick={() => setActiveScanner('delta')}
          style={{
            ...styles.navButton,
            ...(activeScanner === 'delta' ? styles.activeNavButton : {})
          }}
        >
          📊 Delta Scanner
        </button>
        <button
          onClick={() => setActiveScanner('backtest')}
          style={{
            ...styles.navButton,
            ...(activeScanner === 'backtest' ? styles.activeNavButton : {})
          }}
        >
          📈 Backtest
        </button>
      </div>

      {/* Scanner Content */}
      {activeScanner === 'us-weekly' && <UsWeeklyBreakout />}
      {activeScanner === 'nse-weekly' && <WeeklyBreakout />}
      {activeScanner === 'ipo' && <IpoScanner />}
      {activeScanner === 'delta' && <DeltaScanner />}
      {activeScanner === 'backtest' && <Backtest />}
    </div>
  );
}

const styles = {
  container: {
    maxWidth: '1400px',
    margin: '0 auto',
    padding: '20px',
    background: 'white',
    borderRadius: '12px',
    boxShadow: '0 4px 6px rgba(0, 0, 0, 0.1)'
  },
  title: {
    textAlign: 'center',
    color: '#333',
    marginBottom: '30px',
    fontSize: '32px'
  },
  scannerNav: {
    display: 'flex',
    gap: '10px',
    marginBottom: '30px',
    flexWrap: 'wrap',
    justifyContent: 'center'
  },
  navButton: {
    padding: '12px 24px',
    background: '#e0e0e0',
    color: '#333',
    border: 'none',
    borderRadius: '8px',
    cursor: 'pointer',
    fontSize: '14px',
    fontWeight: '600',
    transition: 'all 0.2s'
  },
  activeNavButton: {
    background: '#667eea',
    color: 'white'
  }
};

export default App;
