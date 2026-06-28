import { useState } from 'react';
import { portfolioApi } from '../api';

function Portfolio() {
  const [loading, setLoading] = useState(false);
  const [message, setMessage] = useState('');
  const [selectedBroker, setSelectedBroker] = useState('all');
  const [holdings, setHoldings] = useState(null);

  const brokers = ['all', 'UPSTOX', 'ZERODHA', 'DHAN'];

  const loadHoldings = async (broker) => {
    setLoading(true);
    try {
      let response;
      if (broker === 'all') {
        response = await portfolioApi.getHoldings();
      } else {
        response = await portfolioApi.getHoldingsByBroker(broker);
      }
      setHoldings(response);
      setMessage(`Holdings loaded from ${broker === 'all' ? 'all brokers' : broker}`);
    } catch (error) {
      setMessage('Error loading holdings: ' + error.message);
    }
    setLoading(false);
  };

  const triggerVolumeScan = async () => {
    setLoading(true);
    try {
      const response = await portfolioApi.triggerVolumeScan();
      setMessage(response.status || 'Volume scan triggered successfully');
    } catch (error) {
      setMessage('Error triggering volume scan: ' + error.message);
    }
    setLoading(false);
  };

  const renderHoldingsTable = (items) => {
    if (!items || items.length === 0) {
      return <div style={styles.empty}>No holdings found</div>;
    }

    return (
      <div style={styles.tableContainer}>
        <table style={styles.table}>
          <thead>
            <tr>
              <th style={styles.th}>Symbol</th>
              <th style={styles.th}>Company</th>
              <th style={styles.th}>Quantity</th>
              <th style={styles.th}>Avg Price</th>
              <th style={styles.th}>Last Price</th>
              <th style={styles.th}>Day Change</th>
              <th style={styles.th}>Day Change %</th>
              <th style={styles.th}>P&L</th>
            </tr>
          </thead>
          <tbody>
            {items.map((holding, index) => (
              <tr key={index}>
                <td style={styles.td}>{holding.tradingSymbol}</td>
                <td style={styles.td}>{holding.companyName}</td>
                <td style={styles.td}>{holding.quantity}</td>
                <td style={styles.td}>{holding.averagePrice?.toFixed(2)}</td>
                <td style={styles.td}>{holding.lastPrice?.toFixed(2)}</td>
                <td style={{
                  ...styles.td,
                  color: holding.dayChange >= 0 ? '#28a745' : '#dc3545'
                }}>
                  {holding.dayChange?.toFixed(2)}
                </td>
                <td style={{
                  ...styles.td,
                  color: holding.dayChangePercentage >= 0 ? '#28a745' : '#dc3545'
                }}>
                  {holding.dayChangePercentage?.toFixed(2)}%
                </td>
                <td style={{
                  ...styles.td,
                  color: holding.pnl >= 0 ? '#28a745' : '#dc3545',
                  fontWeight: '600'
                }}>
                  {holding.pnl?.toFixed(2)}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    );
  };

  const calculateTotalPnL = () => {
    if (!holdings || holdings.length === 0) return 0;
    return holdings.reduce((sum, h) => sum + (h.pnl || 0), 0);
  };

  const calculateTotalValue = () => {
    if (!holdings || holdings.length === 0) return 0;
    return holdings.reduce((sum, h) => sum + (h.lastPrice * h.quantity || 0), 0);
  };

  return (
    <div style={styles.container}>
      <h2 style={styles.title}>💼 Portfolio Monitor</h2>
      
      {message && (
        <div style={styles.message}>
          {message}
          <button onClick={() => setMessage('')} style={styles.closeBtn}>×</button>
        </div>
      )}

      <div style={styles.section}>
        <h3 style={styles.sectionTitle}>Broker Selection</h3>
        <div style={styles.brokerTabs}>
          {brokers.map(broker => (
            <button
              key={broker}
              onClick={() => {
                setSelectedBroker(broker);
                loadHoldings(broker);
              }}
              disabled={loading}
              style={{
                ...styles.brokerTab,
                ...(selectedBroker === broker ? styles.activeBrokerTab : {})
              }}
            >
              {broker === 'all' ? '🌐 All Brokers' : broker}
            </button>
          ))}
        </div>
      </div>

      <div style={styles.section}>
        <h3 style={styles.sectionTitle}>Actions</h3>
        <div style={styles.buttonGroup}>
          <button 
            onClick={() => loadHoldings(selectedBroker)} 
            disabled={loading} 
            style={styles.primaryButton}
          >
            🔄 Refresh Holdings
          </button>
          <button 
            onClick={triggerVolumeScan} 
            disabled={loading} 
            style={styles.secondaryButton}
          >
            📊 Trigger Volume & Price Scan
          </button>
        </div>
      </div>

      {holdings && (
        <div style={styles.section}>
          <h3 style={styles.sectionTitle}>
            Portfolio Summary
          </h3>
          <div style={styles.summaryGrid}>
            <div style={styles.summaryCard}>
              <div style={styles.summaryLabel}>Total Holdings</div>
              <div style={styles.summaryValue}>{holdings.length}</div>
            </div>
            <div style={styles.summaryCard}>
              <div style={styles.summaryLabel}>Total Value</div>
              <div style={styles.summaryValue}>₹{calculateTotalValue().toLocaleString('en-IN', {maximumFractionDigits: 0})}</div>
            </div>
            <div style={styles.summaryCard}>
              <div style={styles.summaryLabel}>Total P&L</div>
              <div style={{
                ...styles.summaryValue,
                color: calculateTotalPnL() >= 0 ? '#28a745' : '#dc3545'
              }}>
                ₹{calculateTotalPnL().toLocaleString('en-IN', {maximumFractionDigits: 0})}
              </div>
            </div>
          </div>
        </div>
      )}

      {holdings && (
        <div style={styles.section}>
          <h3 style={styles.sectionTitle}>
            Holdings ({selectedBroker === 'all' ? 'All Brokers' : selectedBroker}) - {holdings.length} stocks
          </h3>
          {renderHoldingsTable(holdings)}
        </div>
      )}

      <div style={styles.section}>
        <h3 style={styles.sectionTitle}>📋 Scan Information</h3>
        <div style={styles.infoBox}>
          <p style={styles.infoText}><strong>Volume Spike Alert:</strong> Triggers when current volume exceeds 1.5x average volume</p>
          <p style={styles.infoText}><strong>Price Movement Alert:</strong> Triggers when price changes by ≥2.5% from previous period</p>
          <p style={styles.infoText}><strong>Timeframes:</strong> Daily, Weekly, Monthly</p>
          <p style={styles.infoText}><strong>Schedule:</strong> Runs daily at 3:35 PM IST</p>
          <p style={styles.infoText}><strong>Alerts sent to:</strong> Telegram & Discord</p>
        </div>
      </div>
    </div>
  );
}

const styles = {
  container: {
    padding: '20px',
    background: '#f8f9fa',
    borderRadius: '8px'
  },
  title: {
    color: '#333',
    marginBottom: '20px'
  },
  message: {
    padding: '12px',
    background: '#d4edda',
    border: '1px solid #c3e6cb',
    borderRadius: '4px',
    marginBottom: '20px',
    display: 'flex',
    justifyContent: 'space-between',
    alignItems: 'center'
  },
  closeBtn: {
    background: 'none',
    border: 'none',
    fontSize: '18px',
    cursor: 'pointer'
  },
  section: {
    background: 'white',
    padding: '20px',
    borderRadius: '8px',
    marginBottom: '20px'
  },
  sectionTitle: {
    color: '#666',
    marginBottom: '15px',
    fontSize: '18px'
  },
  brokerTabs: {
    display: 'flex',
    gap: '10px',
    flexWrap: 'wrap'
  },
  brokerTab: {
    padding: '10px 20px',
    background: '#e0e0e0',
    color: '#333',
    border: 'none',
    borderRadius: '4px',
    cursor: 'pointer',
    fontSize: '14px',
    fontWeight: '500'
  },
  activeBrokerTab: {
    background: '#667eea',
    color: 'white'
  },
  buttonGroup: {
    display: 'flex',
    gap: '10px',
    flexWrap: 'wrap'
  },
  primaryButton: {
    padding: '12px 24px',
    background: '#667eea',
    color: 'white',
    border: 'none',
    borderRadius: '4px',
    cursor: 'pointer',
    fontSize: '14px',
    fontWeight: '500'
  },
  secondaryButton: {
    padding: '12px 24px',
    background: '#6c757d',
    color: 'white',
    border: 'none',
    borderRadius: '4px',
    cursor: 'pointer',
    fontSize: '14px',
    fontWeight: '500'
  },
  summaryGrid: {
    display: 'grid',
    gridTemplateColumns: 'repeat(auto-fit, minmax(200px, 1fr))',
    gap: '15px'
  },
  summaryCard: {
    background: '#f8f9fa',
    padding: '15px',
    borderRadius: '4px',
    textAlign: 'center'
  },
  summaryLabel: {
    color: '#666',
    fontSize: '12px',
    marginBottom: '5px'
  },
  summaryValue: {
    color: '#333',
    fontSize: '18px',
    fontWeight: '600'
  },
  tableContainer: {
    overflowX: 'auto'
  },
  table: {
    width: '100%',
    borderCollapse: 'collapse'
  },
  th: {
    padding: '10px',
    textAlign: 'left',
    background: '#f8f9fa',
    borderBottom: '2px solid #dee2e6',
    fontSize: '12px',
    fontWeight: '600'
  },
  td: {
    padding: '10px',
    borderBottom: '1px solid #dee2e6',
    fontSize: '12px'
  },
  empty: {
    padding: '20px',
    textAlign: 'center',
    color: '#666'
  },
  infoBox: {
    background: '#e7f3ff',
    border: '1px solid #b8daff',
    borderRadius: '4px',
    padding: '15px'
  },
  infoText: {
    margin: '8px 0',
    color: '#333',
    fontSize: '13px'
  }
};

export default Portfolio;
